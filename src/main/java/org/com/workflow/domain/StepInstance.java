package org.com.workflow.domain;

/**
 * One step of one run. {@code priority} and {@code maxAttempts} are copied from the definition at
 * run start so a run is an immutable snapshot: re-registering a workflow cannot change the retry
 * budget of a run already in flight.
 *
 * <p>{@code attemptCount} counts attempts <em>started</em> and increments at claim time, so while a
 * step is {@link StepStatus#IN_PROGRESS} it is also the number of the attempt currently running.
 */
public record StepInstance(
        String stepId,
        StepStatus status,
        int attemptCount,
        int maxAttempts,
        int priority,
        String lastWorkerId) {

    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }
}
