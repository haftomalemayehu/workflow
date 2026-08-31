package org.com.workflow.api;

import org.com.workflow.domain.ConflictException;
import org.com.workflow.domain.NotFoundException;
import org.com.workflow.domain.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the domain's failure kinds onto status codes. The 409s matter: the request was well formed,
 * so the client should re-read state rather than fix its payload.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    ProblemDetail onValidation(ValidationException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage(), "Invalid request");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail onNotFound(NotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), "Not found");
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail onConflict(ConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), "Conflicting state");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
