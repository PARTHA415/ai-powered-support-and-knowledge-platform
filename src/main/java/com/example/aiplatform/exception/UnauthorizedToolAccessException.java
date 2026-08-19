package com.example.aiplatform.exception;

/**
 * The caller (identified by {@link com.example.aiplatform.security.CurrentUser},
 * never by an argument the LLM itself supplied) is not entitled to the
 * resource a tool was asked to look up. The LLM choosing to call a tool with
 * some ID does not imply the current caller is allowed to see that ID's
 * data - that check happens inside the tool, independent of the model.
 */
public class UnauthorizedToolAccessException extends RuntimeException {

    public UnauthorizedToolAccessException(String message) {
        super(message);
    }
}
