package com.nivas.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for errors we deliberately surface to callers.
 *
 * <p>Carrying the status and a stable machine-readable {@code code} on the
 * exception keeps {@link com.nivas.common.GlobalExceptionHandler} free of a
 * growing {@code instanceof} ladder, and gives clients something to branch on
 * that is not the human-readable message.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
