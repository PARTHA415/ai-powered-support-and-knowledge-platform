package com.example.aiplatform.exception;

/**
 * A tool was called with a well-formed but nonexistent identifier - a
 * deterministic "tool failure" distinct from a validation error: the request
 * was valid, the data simply doesn't exist.
 */
public class ToolResourceNotFoundException extends RuntimeException {

    public ToolResourceNotFoundException(String message) {
        super(message);
    }
}
