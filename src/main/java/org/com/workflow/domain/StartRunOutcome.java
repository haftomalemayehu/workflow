package org.com.workflow.domain;

/** {@code created} is false when an existing run was returned for a repeated requestId. */
public record StartRunOutcome(WorkflowRun run, boolean created) {
}
