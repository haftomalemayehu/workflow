package org.com.workflow.service;

import org.com.workflow.domain.ClaimedStep;
import org.com.workflow.domain.ConflictException;
import org.com.workflow.domain.DependencyGraph;
import org.com.workflow.domain.NotFoundException;
import org.com.workflow.domain.RunPlanner;
import org.com.workflow.domain.RunStatus;
import org.com.workflow.domain.RunSummary;
import org.com.workflow.domain.StepInstance;
import org.com.workflow.domain.StartRunOutcome;
import org.com.workflow.domain.StepResult;
import org.com.workflow.domain.StepStatus;
import org.com.workflow.domain.StepSummary;
import org.com.workflow.domain.ValidationException;
import org.com.workflow.domain.WorkflowDefinition;
import org.com.workflow.domain.WorkflowRun;
import org.com.workflow.domain.WorkflowValidator;
import org.com.workflow.persistence.JdbcStepInstanceRepository;
import org.com.workflow.persistence.JdbcWorkflowDefinitionRepository;
import org.com.workflow.persistence.JdbcWorkflowRunRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The five scheduler operations. Every state change runs inside a transaction that the SQLite
 * connection opens with BEGIN IMMEDIATE, so claims and completions serialize against each other.
 */
@Service
public class WorkflowSchedulerService {

    private final JdbcWorkflowDefinitionRepository definitions;
    private final JdbcWorkflowRunRepository runs;
    private final JdbcStepInstanceRepository stepInstances;
    private final TransactionTemplate transactions;

    public WorkflowSchedulerService(
            JdbcWorkflowDefinitionRepository definitions,
            JdbcWorkflowRunRepository runs,
            JdbcStepInstanceRepository stepInstances,
            TransactionTemplate transactions) {
        this.definitions = definitions;
        this.runs = runs;
        this.stepInstances = stepInstances;
        this.transactions = transactions;
    }

    public void registerWorkflow(WorkflowDefinition definition) {
        WorkflowValidator.validate(definition);
        transactions.executeWithoutResult(status -> definitions.save(definition));
    }

    /**
     * Idempotent by (workflowName, requestId). The check and the insert share one IMMEDIATE
     * transaction, and the unique index is the backstop for a writer in another process.
     */
    public StartRunOutcome startRun(String workflowName, String requestId) {
        requireText(requestId, "requestId");
        WorkflowDefinition definition = requireDefinition(workflowName);

        return transactions.execute(status -> {
            Optional<WorkflowRun> existing = runs.findByIdempotencyKey(workflowName, requestId);
            if (existing.isPresent()) {
                return new StartRunOutcome(existing.get(), false);
            }

            WorkflowRun run = new WorkflowRun(
                    UUID.randomUUID().toString(), workflowName, requestId, RunStatus.RUNNING);
            try {
                runs.insert(run);
            } catch (DuplicateKeyException raced) {
                // The unique index is the arbiter, so a concurrent submitter already won.
                return new StartRunOutcome(
                        runs.findByIdempotencyKey(workflowName, requestId).orElseThrow(), false);
            }
            stepInstances.createAll(run.runId(), definition.steps());

            // A run with nothing to do is already terminal; without this the stored status would
            // stay RUNNING until some transition happened to recompute it.
            RunStatus computed = RunPlanner.runStatus(
                    stepInstances.findByRunId(run.runId()), graphOf(run.runId()));
            if (computed != run.runStatus()) {
                runs.updateStatus(run.runId(), computed);
                return new StartRunOutcome(
                        new WorkflowRun(run.runId(), workflowName, requestId, computed), true);
            }
            return new StartRunOutcome(run, true);
        });
    }

    /**
     * Claims up to {@code maxCount} runnable steps. The whole batch runs in one transaction; the
     * repository's compare-and-swap drops any step another writer moved first, so a step is never
     * handed to two workers.
     */
    public List<ClaimedStep> claim(String runId, String workerId, int maxCount) {
        requireText(workerId, "workerId");
        if (maxCount <= 0) {
            throw new ValidationException(List.of("maxCount must be > 0"));
        }

        return transactions.execute(status -> {
            requireRun(runId);
            DependencyGraph graph = graphOf(runId);
            List<StepInstance> candidates =
                    RunPlanner.claimable(stepInstances.findByRunId(runId), graph, maxCount);

            List<ClaimedStep> claimed = new ArrayList<>();
            for (StepInstance candidate : candidates) {
                if (stepInstances.tryClaim(runId, candidate, workerId)) {
                    int attemptNumber = candidate.attemptCount() + 1;
                    stepInstances.appendEvent(
                            runId, candidate.stepId(), attemptNumber, "claimed", workerId);
                    claimed.add(new ClaimedStep(candidate.stepId(), attemptNumber));
                }
            }
            refreshRunStatus(runId, graph);
            return List.copyOf(claimed);
        });
    }

    /**
     * Completes one claimed attempt. Only the worker holding the step, on the attempt it holds, may
     * complete it; anything else is a conflict rather than a bad request.
     */
    public void completeStep(
            String runId, String stepId, String workerId, int attemptNumber, StepResult result) {

        requireText(workerId, "workerId");
        if (result == null) {
            throw new ValidationException(List.of("result must be 'success' or 'fail'"));
        }

        transactions.executeWithoutResult(status -> {
            requireRun(runId);
            DependencyGraph graph = graphOf(runId);
            StepInstance step = stepInstances.findByRunId(runId).stream()
                    .filter(candidate -> candidate.stepId().equals(stepId))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException(
                            "unknown stepId '%s' in run '%s'".formatted(stepId, runId)));

            if (step.status() != StepStatus.IN_PROGRESS) {
                throw new ConflictException(
                        "step '%s' is not in_progress".formatted(stepId));
            }
            if (!workerId.equals(step.lastWorkerId())) {
                throw new ConflictException("step '%s' is held by a different worker"
                        .formatted(stepId));
            }
            if (step.attemptCount() != attemptNumber) {
                throw new ConflictException("step '%s' is on attempt %d, not %d"
                        .formatted(stepId, step.attemptCount(), attemptNumber));
            }

            StepStatus outcome = switch (result) {
                case SUCCESS -> StepStatus.SUCCEEDED;
                // attemptCount already includes the attempt being completed.
                case FAIL -> step.hasAttemptsRemaining() ? StepStatus.PENDING : StepStatus.FAILED;
            };

            stepInstances.applyCompletion(runId, stepId, outcome);
            stepInstances.appendEvent(runId, stepId, attemptNumber,
                    result == StepResult.SUCCESS ? "succeeded" : "failed", workerId);
            refreshRunStatus(runId, graph);
        });
    }

    private DependencyGraph graphOf(String runId) {
        return stepInstances.findGraph(runId);
    }

    private void refreshRunStatus(String runId, DependencyGraph graph) {
        runs.updateStatus(runId, RunPlanner.runStatus(stepInstances.findByRunId(runId), graph));
    }

    public RunSummary runSummary(String runId) {
        return transactions.execute(status -> {
            WorkflowRun run = requireRun(runId);
            DependencyGraph graph = graphOf(runId);

            List<StepInstance> steps = stepInstances.findByRunId(runId);
            Set<String> blocked = RunPlanner.blocked(steps, graph);

            List<StepSummary> summaries = steps.stream()
                    .map(step -> new StepSummary(
                            step.stepId(),
                            blocked.contains(step.stepId()) ? StepStatus.BLOCKED : step.status(),
                            step.attemptCount(),
                            step.lastWorkerId()))
                    .toList();

            return new RunSummary(runId, run.workflowName(), run.runStatus(), summaries);
        });
    }

    private WorkflowDefinition requireDefinition(String workflowName) {
        return definitions.findByName(workflowName)
                .orElseThrow(() -> new NotFoundException("unknown workflowName '%s'".formatted(workflowName)));
    }

    private WorkflowRun requireRun(String runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new NotFoundException("unknown runId '%s'".formatted(runId)));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(List.of(field + " must not be blank"));
        }
    }
}
