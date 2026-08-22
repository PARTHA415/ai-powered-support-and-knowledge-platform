package com.example.aiplatform.model;

import java.util.List;

/**
 * mcpToolsDiscovered is the tool names returned by the MCP {@code tools/list}
 * call the demo client makes before asking the LLM anything - included
 * specifically so callers (and the Phase 13 exercises) can see, concretely,
 * that these came from a real MCP protocol round trip rather than being
 * hardcoded, the same way {@link AskResponse#sources()} lets a caller verify
 * a RAG answer was actually grounded.
 */
public record McpAssistantResponse(
        String answer,
        String model,
        List<String> mcpToolsDiscovered
) {
}
