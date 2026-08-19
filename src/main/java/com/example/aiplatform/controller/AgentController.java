package com.example.aiplatform.controller;

import com.example.aiplatform.model.AgentRequest;
import com.example.aiplatform.model.AgentResponse;
import com.example.aiplatform.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @Operation(summary = "Ask the agent a question; it plans whether knowledge-base search and/or a "
            + "business tool are needed, calls them, and combines the results into one grounded answer")
    @PostMapping("/ask")
    public ResponseEntity<AgentResponse> ask(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(agentService.handle(request.conversationId(), request.message()));
    }
}
