package org.com.workflow.domain;

import java.util.List;

public record StepDefinition(String stepId, int priority, int maxAttempts, List<String> dependencies) {
}
