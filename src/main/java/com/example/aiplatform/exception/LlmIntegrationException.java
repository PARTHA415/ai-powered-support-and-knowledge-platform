package com.example.aiplatform.exception;

public class LlmIntegrationException extends RuntimeException {

    public LlmIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
