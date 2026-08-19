package com.example.aiplatform.controller;

import com.example.aiplatform.model.AskRequest;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.service.QuestionAnsweringService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa")
public class QuestionAnsweringController {

    private final QuestionAnsweringService questionAnsweringService;

    public QuestionAnsweringController(QuestionAnsweringService questionAnsweringService) {
        this.questionAnsweringService = questionAnsweringService;
    }

    @Operation(summary = "Answer a question using retrieval-augmented generation over the knowledge base, with source citations")
    @PostMapping
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        return ResponseEntity.ok(questionAnsweringService.answer(request.question()));
    }
}
