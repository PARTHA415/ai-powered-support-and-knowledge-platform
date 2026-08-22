package com.example.aiplatform.mcp;

import com.example.aiplatform.model.McpAssistantResponse;

/**
 * Demonstrates the "AI Client -&gt; MCP -&gt; Java Application" path, as a
 * direct comparison against {@link com.example.aiplatform.service.SupportAssistantService}
 * (Phase 8's "LLM -&gt; Java Tool" in-process function calling). Instead of
 * handing the LLM Java tool objects directly, this builds a real MCP
 * client, connects to this application's OWN MCP server over HTTP,
 * discovers tools via the MCP protocol ({@code tools/list}), and lets the
 * LLM invoke them through that connection ({@code tools/call}) - the exact
 * same {@code SupportTools} methods, reached a different way.
 */
public interface McpDemoClientService {

    /**
     * @param message                  the caller's support question.
     * @param authorizationHeaderValue the caller's own {@code Authorization}
     *                                 header value (e.g. {@code "Basic
     *                                 ..."}), forwarded verbatim to the
     *                                 outbound MCP connection so the MCP
     *                                 server authenticates and authorizes as
     *                                 the SAME caller - MCP never gets its
     *                                 own, separate identity.
     */
    McpAssistantResponse assist(String message, String authorizationHeaderValue);
}
