package org.com.workflow.domain;

public record StepSummary(String stepId, StepStatus status, int attemptCount, String lastWorkerId) {
}
