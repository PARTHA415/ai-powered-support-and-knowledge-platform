package com.example.aiplatform.controller;

import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAssistantRequest;
import com.example.aiplatform.service.SupportAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support")
public class SupportAssistantController {

    private final SupportAssistantService supportAssistantService;

    public SupportAssistantController(SupportAssistantService supportAssistantService) {
        this.supportAssistantService = supportAssistantService;
    }

    @Operation(summary = "Ask the support assistant a question; it may call order/payment/shipment/"
            + "customer/inventory tools as needed")
    @PostMapping("/assist")
    public ResponseEntity<ChatResponse> assist(@Valid @RequestBody SupportAssistantRequest request) {
        return ResponseEntity.ok(supportAssistantService.assist(request.customerId(), request.message()));
    }
}
