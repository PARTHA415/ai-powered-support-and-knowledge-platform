package com.example.aiplatform.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", message));
    }

    @ExceptionHandler(LlmIntegrationException.class)
    public ResponseEntity<ErrorResponse> handleLlmIntegration(LlmIntegrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(), "Bad Gateway", ex.getMessage()));
    }

    @ExceptionHandler(InvalidToolArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToolArgument(InvalidToolArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage()));
    }

    @ExceptionHandler(ToolResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleToolResourceNotFound(ToolResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedToolAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedToolAccess(UnauthorizedToolAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage()));
    }

    @ExceptionHandler(PromptInjectionException.class)
    public ResponseEntity<ErrorResponse> handlePromptInjection(PromptInjectionException ex) {
        log.warn("Blocked a request matching a prompt-injection pattern: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage()));
    }

    @ExceptionHandler(PromptTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePromptTooLarge(PromptTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Payload Too Large", ex.getMessage()));
    }

    @ExceptionHandler(ToolExecutionLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleToolExecutionLimitExceeded(ToolExecutionLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        "An unexpected error occurred"));
    }
}
