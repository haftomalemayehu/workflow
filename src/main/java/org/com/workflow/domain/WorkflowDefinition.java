package org.com.workflow.domain;

import java.util.List;

public record WorkflowDefinition(String workflowName, List<StepDefinition> steps) {
}
