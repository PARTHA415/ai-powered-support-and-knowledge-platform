package com.example.aiplatform.controller;

import com.example.aiplatform.model.ChatRequest;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAnswer;
import com.example.aiplatform.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "Send a message to the LLM and get a structured answer (category, confidence, escalation flag)")
    @PostMapping("/structured")
    public ResponseEntity<SupportAnswer> chatStructured(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.answerStructured(request.message()));
    }
}
