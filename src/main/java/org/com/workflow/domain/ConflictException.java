package org.com.workflow.domain;

/**
 * The request was well formed but lost a race, or the worker is out of date. Distinct from
 * {@link ValidationException} because it tells a client to re-read state, not fix its payload.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
