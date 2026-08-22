package com.example.aiplatform.config;

import com.example.aiplatform.ai.tools.SupportTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the SAME {@link SupportTools} instance already used for
 * in-process LLM tool-calling (Phase 8) as a {@code ToolCallbackProvider}
 * bean. Spring AI's MCP server autoconfiguration (from
 * spring-ai-starter-mcp-server-webmvc) automatically discovers every
 * {@code ToolCallbackProvider}/{@code ToolCallback} bean in the context and
 * exposes it over the MCP server - so this one bean definition is the
 * entire "expose selected capabilities via MCP" step. No new tool
 * implementation, no new authorization logic: {@code getOrder},
 * {@code getPaymentStatus}, {@code getCustomer}, {@code searchKnowledgeBase}
 * (and the rest of {@link SupportTools}) become MCP tools exactly as they
 * already are, ownership checks and all - see the Phase 13 docs for why
 * that reuse is the whole point.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider supportToolsMcpProvider(SupportTools supportTools) {
        return MethodToolCallbackProvider.builder().toolObjects(supportTools).build();
    }
}
