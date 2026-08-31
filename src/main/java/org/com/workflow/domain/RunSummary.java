package org.com.workflow.domain;

import java.util.List;

public record RunSummary(
        String runId, String workflowName, RunStatus runStatus, List<StepSummary> steps) {
}
