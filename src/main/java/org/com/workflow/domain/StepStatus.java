package org.com.workflow.domain;

/**
 * The first four values are persisted. {@link #BLOCKED} never is: it is derived from dependency
 * state on every read, so there is no cached flag to invalidate when a retry returns a step to
 * {@link #PENDING}.
 */
public enum StepStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    BLOCKED;

    public boolean isPersistable() {
        return this != BLOCKED;
    }
}
