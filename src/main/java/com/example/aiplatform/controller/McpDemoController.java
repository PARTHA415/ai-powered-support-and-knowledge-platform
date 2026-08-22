package com.example.aiplatform.controller;

import com.example.aiplatform.mcp.McpDemoClientService;
import com.example.aiplatform.model.McpAssistantRequest;
import com.example.aiplatform.model.McpAssistantResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same authorization rule as every other endpoint - just authenticated, no
 * role restriction - matching {@link SupportAssistantController}, the
 * direct-tool-calling equivalent this is meant to be compared against. No
 * SecurityConfig change was needed for this controller: the fallback
 * {@code anyRequest().authenticated()} rule already covers {@code /mcp}
 * and this endpoint alike, which is itself part of the Phase 13 point - MCP
 * did not require carving out new security rules.
 */
@RestController
@RequestMapping("/api/mcp-demo")
public class McpDemoController {

    private final McpDemoClientService mcpDemoClientService;

    public McpDemoController(McpDemoClientService mcpDemoClientService) {
        this.mcpDemoClientService = mcpDemoClientService;
    }

    @Operation(summary = "Ask the support assistant via MCP: a real MCP client discovers and calls the same "
            + "SupportTools methods (getOrder, getPaymentStatus, getCustomer, searchKnowledgeBase, ...) over "
            + "this application's own MCP server, instead of the LLM calling them in-process - compare with "
            + "POST /api/support/assist. The caller's own credentials are forwarded to the MCP connection, so "
            + "authorization is enforced identically either way.")
    @PostMapping("/assist")
    public ResponseEntity<McpAssistantResponse> assist(@Valid @RequestBody McpAssistantRequest request,
                                                         HttpServletRequest httpServletRequest) {
        String authorizationHeader = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(mcpDemoClientService.assist(request.message(), authorizationHeader));
    }
}
