package org.com.workflow.api;

import org.com.workflow.domain.RunSummary;
import org.com.workflow.domain.StepDefinition;
import org.com.workflow.domain.WorkflowDefinition;
import org.com.workflow.domain.WorkflowRun;

import java.util.List;
import java.util.Locale;

/** Request and response shapes. Kept separate from the domain so JSON never dictates the model. */
final class ApiPayloads {

    private ApiPayloads() {
    }

    record RegisterWorkflowRequest(String workflowName, List<StepPayload> steps) {

        record StepPayload(String stepId, int priority, int maxAttempts, List<String> dependencies) {
        }

        WorkflowDefinition toDomain() {
            List<StepDefinition> definitions = steps == null ? List.of() : steps.stream()
                    .map(step -> new StepDefinition(
                            step.stepId(),
                            step.priority(),
                            step.maxAttempts(),
                            step.dependencies() == null ? List.of() : step.dependencies()))
                    .toList();
            return new WorkflowDefinition(workflowName, definitions);
        }
    }

    record StartRunRequest(String requestId) {
    }

    record ClaimRequest(String workerId, Integer maxCount) {
    }

    record CompleteStepRequest(String workerId, Integer attemptNumber, String result) {
    }

    record ClaimedStepResponse(String stepId, int attemptNumber) {
    }

    record RunResponse(String runId, String workflowName, String runStatus) {

        static RunResponse from(WorkflowRun run) {
            return new RunResponse(run.runId(), run.workflowName(), lower(run.runStatus().name()));
        }
    }

    record StepSummaryResponse(
            String stepId, String status, int attemptCount, String lastWorkerId) {
    }

    record RunSummaryResponse(
            String runId, String workflowName, String runStatus, List<StepSummaryResponse> steps) {

        static RunSummaryResponse from(RunSummary summary) {
            return new RunSummaryResponse(
                    summary.runId(),
                    summary.workflowName(),
                    lower(summary.runStatus().name()),
                    summary.steps().stream()
                            .map(step -> new StepSummaryResponse(
                                    step.stepId(),
                                    lower(step.status().name()),
                                    step.attemptCount(),
                                    step.lastWorkerId()))
                            .toList());
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
