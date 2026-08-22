package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Phase 13 MCP client demonstration
 * ({@code com.example.aiplatform.mcp.McpDemoClientServiceImpl}): where to
 * find this application's OWN MCP server. In a real deployment, an MCP
 * client would normally live in a separate AI application/host connecting
 * to a remote MCP server over the network; this app also embeds a small
 * client so the "AI Client -> MCP -> Java Application" round trip is
 * demonstrable and testable without external tooling.
 */
@ConfigurationProperties(prefix = "app.mcp.demo-client")
public record McpDemoClientProperties(
        @DefaultValue("http://localhost:8080") String baseUrl
) {
}
