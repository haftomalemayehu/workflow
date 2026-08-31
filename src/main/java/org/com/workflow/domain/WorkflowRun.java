package org.com.workflow.domain;

public record WorkflowRun(
        String runId, String workflowName, String requestId, RunStatus runStatus) {
}
