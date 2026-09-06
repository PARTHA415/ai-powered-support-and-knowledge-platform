package com.example.aiplatform.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * A request body Jackson could not parse at all - truncated JSON, a
     * mismatched type, an empty body on a {@code @RequestBody} endpoint.
     *
     * <p>Without this handler the exception reaches the {@code Exception}
     * catch-all below and the caller gets a 500. That is wrong twice over: it
     * tells the client the server broke when the client sent bad input, and it
     * files a client mistake under server errors in the metrics, where a burst
     * of malformed requests looks exactly like an outage.
     *
     * <p>The parser's own message is deliberately not returned - it quotes the
     * offending bytes, which echoes attacker-supplied content back and can
     * expose internal field names.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Rejected unparseable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                        "Malformed request body: expected valid JSON"));
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

    /**
     * Mapped to 404, not the 403 the exception's name suggests, and that is
     * the whole point rather than an inconsistency.
     *
     * <p>A 403 here would tell a caller "this resource exists, but is not
     * yours" - an existence oracle that turns the short, sequential order-ID
     * space into something a customer can enumerate purely from status codes.
     * The response is therefore byte-identical to a genuine miss: same status,
     * same message (see {@code SupportTools.orderNotFoundMessage}). The same
     * reasoning makes a code-hosting site 404 a private repository rather than
     * 403 it.
     *
     * <p>The denial is not hidden from US, only from the caller: the distinct
     * exception type survives in logs and metrics, and {@code AuditLogger} has
     * already recorded a DENY line naming the real caller and the real
     * resource. This handler is reachable at all only because
     * {@code ToolExecutionConfig} rethrows this exception out of the
     * tool-calling loop instead of handing its text to the model.
     */
    @ExceptionHandler(UnauthorizedToolAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedToolAccess(UnauthorizedToolAccessException ex) {
        log.warn("Denied tool access to a resource the caller does not own; "
                + "responding as Not Found to avoid confirming the resource exists");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
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

    /**
     * 429, the same status as the request-count limiter and the tool-call cap.
     * All three say the same thing to a client - you may retry later, this is
     * about you and not about the service - and giving them one status keeps
     * client retry logic from having to distinguish between three flavours of
     * "you have had enough for now".
     */
    @ExceptionHandler(TokenBudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handleTokenBudgetExceeded(TokenBudgetExceededException ex) {
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
