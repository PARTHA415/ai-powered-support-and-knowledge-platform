package com.example.aiplatform.controller;

import com.example.aiplatform.model.ChatRequest;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAnswer;
import com.example.aiplatform.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(summary = "Send a message to the LLM and get a direct answer")
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.answer(request.message()));
    }

    /**
     * Streamed chat, as server-sent events.
     *
     * <p>Returning a {@code Flux} from a Spring MVC controller works because
     * Spring adapts a reactive return type onto the servlet container's async
     * support: the request is put into async mode, the servlet thread is
     * RELEASED, and each element is written as it arrives. That last part is
     * what makes this more than a cosmetic change - the thread-per-request
     * ceiling this application otherwise has does not apply for the several
     * seconds a generation takes, so a slow answer no longer holds a container
     * thread hostage for its whole duration.
     *
     * <p>{@code TEXT_EVENT_STREAM_VALUE} rather than a chunked plain-text body
     * so a browser can consume it with {@code EventSource} and so intermediaries
     * do not buffer it - a proxy that buffers the response reintroduces exactly
     * the wait that streaming exists to remove.
     */
    @Operation(summary = "Send a message to the LLM and stream the answer back token by token as "
            + "server-sent events. Open-ended chat only - see ChatService.answerStreaming for why the "
            + "grounded endpoints stay buffered.")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreaming(@Valid @RequestBody ChatRequest request) {
        return chatService.answerStreaming(request.message());
    }

    @Operation(summary = "Send a message to the LLM and get a structured answer (category, confidence, escalation flag)")
    @PostMapping("/structured")
    public ResponseEntity<SupportAnswer> chatStructured(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.answerStructured(request.message()));
    }
}
