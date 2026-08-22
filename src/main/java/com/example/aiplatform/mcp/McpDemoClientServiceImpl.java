package com.example.aiplatform.mcp;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.config.McpDemoClientProperties;
import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.model.McpAssistantResponse;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The client half of Phase 13's demonstration. {@link #assist} performs a
 * full MCP round trip on every call:
 * <ol>
 *   <li>Open an HTTP-based MCP connection to this application's own
 *       {@code /mcp} endpoint, carrying the caller's own credentials.</li>
 *   <li>{@code initialize()} - the MCP handshake.</li>
 *   <li>{@code listTools()} - protocol-level tool discovery
 *       ({@code tools/list}), returned to the caller as
 *       {@link McpAssistantResponse#mcpToolsDiscovered()} so the round trip
 *       is directly observable, not just asserted.</li>
 *   <li>Hand those MCP-discovered tools to the LLM via
 *       {@link LlmClientService#generateWithTools(Prompt, ToolCallbackProvider)} -
 *       the model decides whether/which tool to call, exactly as it would
 *       for Phase 8's in-process tools, except each call now serializes to
 *       JSON-RPC, crosses HTTP, and is re-authenticated and re-authorized by
 *       this application's own MCP server before the underlying
 *       {@code SupportTools} method ever runs.</li>
 * </ol>
 * A fresh client is opened and closed per call - simple and easy to reason
 * about, appropriate for a demonstration/debug capability rather than a
 * high-throughput production path (see the Phase 13 docs).
 */
@Service
public class McpDemoClientServiceImpl implements McpDemoClientService {

    private static final Logger log = LoggerFactory.getLogger(McpDemoClientServiceImpl.class);
    private static final String MCP_ENDPOINT_PATH = "/mcp";

    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final McpDemoClientProperties properties;
    private final String model;

    public McpDemoClientServiceImpl(PromptBuilder promptBuilder,
                                     LlmClientService llmClientService,
                                     PromptInjectionGuard promptInjectionGuard,
                                     McpDemoClientProperties properties,
                                     @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.properties = properties;
        this.model = model;
    }

    @Override
    public McpAssistantResponse assist(String message, String authorizationHeaderValue) {
        promptInjectionGuard.assertSafe(message);

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(properties.baseUrl())
                .endpoint(MCP_ENDPOINT_PATH)
                .customizeRequest(requestBuilder -> requestBuilder.header("Authorization", authorizationHeaderValue))
                .build();

        McpSyncClient mcpClient = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("ai-platform-mcp-demo-client", "1.0.0"))
                .build();
        try {
            mcpClient.initialize();

            List<String> discoveredTools = mcpClient.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();
            log.info("MCP client discovered {} tool(s) via tools/list: {}", discoveredTools.size(), discoveredTools);

            ToolCallbackProvider mcpTools = new SyncMcpToolCallbackProvider(mcpClient);
            Prompt prompt = promptBuilder.buildToolsSupportPrompt(message);
            String answer = llmClientService.generateWithTools(prompt, mcpTools);

            return new McpAssistantResponse(answer, model, discoveredTools);
        } catch (RuntimeException e) {
            log.error("MCP client round trip failed", e);
            throw new LlmIntegrationException("Failed to complete the MCP-routed request", e);
        } finally {
            mcpClient.closeGracefully();
        }
    }
}
