package com.example.aiplatform.exception;

/**
 * A tool was called with an argument that fails validation - e.g. a
 * malformed ID. Tool arguments are model-generated JSON, not trusted input,
 * so they get validated exactly like an HTTP request body would be.
 */
public class InvalidToolArgumentException extends RuntimeException {

    public InvalidToolArgumentException(String message) {
        super(message);
    }
}
