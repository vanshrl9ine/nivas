package com.nivas.common;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.nivas.common.exception.ApiException;
import com.nivas.common.exception.RateLimitExceededException;

import jakarta.validation.ConstraintViolationException;

/**
 * Translates everything that escapes a controller into RFC 9457 problem details.
 *
 * <p>Two rules drive this class. First, a client should never have to parse a
 * message string to react to an error, so every response carries a stable
 * {@code code}. Second, an unexpected exception must not leak internals: we log
 * the cause with the trace id and return a generic body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI BLANK_TYPE = URI.create("about:blank");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getMessage(), ex.getCode());

        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof RateLimitExceededException rateLimited) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimited.getRetryAfter().toSeconds()));
        }
        return new ResponseEntity<>(problem, headers, ex.getStatus());
    }

    /** Bean-validation failures on {@code @RequestBody} arguments. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() == null ? "is invalid" : fieldError.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed", "validation_failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Validation on {@code @RequestParam} / {@code @PathVariable} arguments. */
    @ExceptionHandler({ HandlerMethodValidationException.class, ConstraintViolationException.class })
    public ProblemDetail handleInvalidParameters(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed", "validation_failed");
    }

    /**
     * A unique or check constraint fired. The DB is the last line of defence and
     * it is expected to reject things the service layer already tried to catch,
     * so this is a 409 rather than a 500 — but it is still worth logging.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint rejected a write", ex);
        return problem(HttpStatus.CONFLICT, "The request conflicts with existing data", "constraint_violation");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT,
                "This record was modified by someone else. Reload it and try again.", "stale_write");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "You do not have access to this resource", "forbidden");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication is required", "unauthenticated");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side", "internal_error");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setTitle(status.getReasonPhrase());

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("code", code);
        extras.put("timestamp", Instant.now().toString());
        extras.forEach(problem::setProperty);
        return problem;
    }
}
