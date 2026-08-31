package org.com.workflow.service;

import org.com.workflow.domain.ClaimedStep;
import org.com.workflow.domain.ConflictException;
import org.com.workflow.domain.NotFoundException;
import org.com.workflow.domain.RunStatus;
import org.com.workflow.domain.StartRunOutcome;
import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.StepResult;
import org.com.workflow.domain.StepStatus;
import org.com.workflow.domain.StepSummary;
import org.com.workflow.domain.ValidationException;
import org.com.workflow.domain.WorkflowDefinition;
import org.com.workflow.domain.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.com.workflow.service.SchedulerTestFixtures.MODEL_PUBLISH;
import static org.com.workflow.service.SchedulerTestFixtures.serviceOn;

class WorkflowSchedulerServiceTest {

    @TempDir
    Path directory;

    private WorkflowSchedulerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = serviceOn(directory);
    }

    @Test
    void startingARunCreatesOneInstancePerStep() {
        service.registerWorkflow(MODEL_PUBLISH);

        WorkflowRun run = service.startRun("model-publish", "req-1001").run();

        assertThat(run.workflowName()).isEqualTo("model-publish");
        assertThat(run.runId()).isNotBlank();
        assertThat(service.runSummary(run.runId()).steps()).hasSize(5);
    }

    @Test
    void startingARunIsIdempotentByRequestId() {
        service.registerWorkflow(MODEL_PUBLISH);

        WorkflowRun first = service.startRun("model-publish", "req-1001").run();
        WorkflowRun second = service.startRun("model-publish", "req-1001").run();

        assertThat(second.runId()).isEqualTo(first.runId());
    }

    @Test
    void adifferentRequestIdStartsADistinctRun() {
        service.registerWorkflow(MODEL_PUBLISH);

        WorkflowRun first = service.startRun("model-publish", "req-1001").run();
        WorkflowRun second = service.startRun("model-publish", "req-1002").run();

        assertThat(second.runId()).isNotEqualTo(first.runId());
    }

    @Test
    void rejectsAnInvalidWorkflowDefinition() {
        WorkflowDefinition cyclic = new WorkflowDefinition("bad", List.of(
                new StepDefinition("a", 1, 1, List.of("b")),
                new StepDefinition("b", 1, 1, List.of("a"))));

        assertThatThrownBy(() -> service.registerWorkflow(cyclic))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cycle");
    }

    // --- the worked example from the exercise brief ---

    @Test
    void reproducesTheScenarioFromTheBrief() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "req-1001").run().runId();

        assertThat(service.claim(runId, "worker-a", 2))
                .containsExactly(new ClaimedStep("load-model", 1));

        service.completeStep(runId, "load-model", "worker-a", 1, StepResult.SUCCESS);

        assertThat(service.claim(runId, "worker-a", 2)).containsExactly(
                new ClaimedStep("validate-schema", 1),
                new ClaimedStep("write-audit", 1));

        service.completeStep(runId, "write-audit", "worker-a", 1, StepResult.SUCCESS);
        service.completeStep(runId, "validate-schema", "worker-a", 1, StepResult.FAIL);

        assertThat(service.claim(runId, "worker-b", 2))
                .containsExactly(new ClaimedStep("validate-schema", 2));
    }

    // --- claim guards ---

    @Test
    void claimRejectsNonPositiveMaxCount() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();

        assertThatThrownBy(() -> service.claim(runId, "worker-a", 0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxCount");
    }

    @Test
    void claimRejectsBlankWorkerId() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();

        assertThatThrownBy(() -> service.claim(runId, "  ", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("workerId");
    }

    @Test
    void claimRejectsAnUnknownRunId() {
        assertThatThrownBy(() -> service.claim("ghost", "worker-a", 1))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ghost");
    }

    // --- completion guards ---

    @Test
    void completeRejectsTheWrongWorker() {
        String runId = startedRunWithLoadModelClaimedBy("worker-a");

        assertThatThrownBy(() ->
                service.completeStep(runId, "load-model", "worker-b", 1, StepResult.SUCCESS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("worker");
    }

    @Test
    void completeRejectsTheWrongAttemptNumber() {
        String runId = startedRunWithLoadModelClaimedBy("worker-a");

        assertThatThrownBy(() ->
                service.completeStep(runId, "load-model", "worker-a", 7, StepResult.SUCCESS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void completeRejectsAStepThatIsNotInProgress() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();

        assertThatThrownBy(() ->
                service.completeStep(runId, "load-model", "worker-a", 1, StepResult.SUCCESS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("in_progress");
    }

    @Test
    void completeRejectsAnUnknownStepId() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();

        assertThatThrownBy(() ->
                service.completeStep(runId, "ghost", "worker-a", 1, StepResult.SUCCESS))
                .isInstanceOf(NotFoundException.class);
    }

    // --- retries and terminal state ---

    @Test
    void aStepWithNoAttemptsLeftFailsAndBlocksItsDependentsTransitively() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();

        service.claim(runId, "w", 1);
        service.completeStep(runId, "load-model", "w", 1, StepResult.SUCCESS);
        service.claim(runId, "w", 2);
        service.completeStep(runId, "write-audit", "w", 1, StepResult.SUCCESS);
        service.completeStep(runId, "validate-schema", "w", 1, StepResult.FAIL);
        service.claim(runId, "w", 1);
        service.completeStep(runId, "validate-schema", "w", 2, StepResult.FAIL);

        assertThat(service.runSummary(runId).steps())
                .extracting(StepSummary::stepId, StepSummary::status)
                .containsExactly(
                        tuple("load-model", StepStatus.SUCCEEDED),
                        tuple("persist-metadata", StepStatus.BLOCKED),
                        tuple("publish-event", StepStatus.BLOCKED),
                        tuple("validate-schema", StepStatus.FAILED),
                        tuple("write-audit", StepStatus.SUCCEEDED));
        assertThat(service.runSummary(runId).runStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void aRunSucceedsOnceEveryStepHasSucceeded() {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();

        for (int guard = 0; guard < 10; guard++) {
            List<ClaimedStep> claimed = service.claim(runId, "w", 10);
            if (claimed.isEmpty()) {
                break;
            }
            claimed.forEach(step -> service.completeStep(
                    runId, step.stepId(), "w", step.attemptNumber(), StepResult.SUCCESS));
        }

        assertThat(service.runSummary(runId).runStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void theAttemptCountReportedInTheSummaryTracksClaims() {
        String runId = startedRunWithLoadModelClaimedBy("worker-a");

        assertThat(service.runSummary(runId).steps())
                .filteredOn(step -> step.stepId().equals("load-model"))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.status()).isEqualTo(StepStatus.IN_PROGRESS);
                    assertThat(step.attemptCount()).isEqualTo(1);
                    assertThat(step.lastWorkerId()).isEqualTo("worker-a");
                });
    }

    private String startedRunWithLoadModelClaimedBy(String workerId) {
        service.registerWorkflow(MODEL_PUBLISH);
        String runId = service.startRun("model-publish", "r").run().runId();
        service.claim(runId, workerId, 1);
        return runId;
    }

    // --- a run is a snapshot: re-registering a workflow must not steer a run already in flight ---

    @Test
    void anInFlightRunKeepsTheDependencyEdgesItStartedWith() {
        service.registerWorkflow(new WorkflowDefinition("wf", List.of(
                new StepDefinition("a", 1, 1, List.of()),
                new StepDefinition("b", 9, 1, List.of("a")))));
        String runId = service.startRun("wf", "r").run().runId();

        // b's dependency is removed after the run started; the run must not notice.
        service.registerWorkflow(new WorkflowDefinition("wf", List.of(
                new StepDefinition("a", 1, 1, List.of()),
                new StepDefinition("b", 9, 1, List.of()))));

        assertThat(service.claim(runId, "w", 5))
                .extracting(ClaimedStep::stepId)
                .containsExactly("a");
    }

    @Test
    void anInFlightRunIsNotBlockedByADependencyAddedAfterItStarted() {
        service.registerWorkflow(new WorkflowDefinition("wf", List.of(
                new StepDefinition("a", 1, 1, List.of()),
                new StepDefinition("b", 9, 1, List.of()))));
        String runId = service.startRun("wf", "r").run().runId();

        service.registerWorkflow(new WorkflowDefinition("wf", List.of(
                new StepDefinition("a", 1, 1, List.of()),
                new StepDefinition("b", 9, 1, List.of("a")))));

        assertThat(service.claim(runId, "w", 5))
                .extracting(ClaimedStep::stepId)
                .containsExactly("b", "a");
    }

    // --- run status must be correct from the moment the run exists, not only after a transition ---

    @Test
    void runStatusIsComputedWhenTheRunIsCreated() {
        service.registerWorkflow(new WorkflowDefinition("empty", List.of()));

        StartRunOutcome outcome = service.startRun("empty", "r");

        assertThat(outcome.run().runStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(service.runSummary(outcome.run().runId()).runStatus())
                .isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void aRunWithWorkStillToDoStartsAsRunning() {
        service.registerWorkflow(MODEL_PUBLISH);

        assertThat(service.startRun("model-publish", "r").run().runStatus())
                .isEqualTo(RunStatus.RUNNING);
    }
}
