package com.example.aiplatform.mcp;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.llm.LlmClientService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The centerpiece demonstration for Phase 13: a REAL MCP client (the same
 * {@code io.modelcontextprotocol.sdk} classes any external AI application
 * would use, not a shortcut) connects over real HTTP to this application's
 * own MCP server, discovers tools via {@code tools/list}, and calls one via
 * {@code tools/call} - proving both that the "AI Client -&gt; MCP -&gt; Java
 * Application" path genuinely works end to end, AND that authorization is
 * enforced exactly as it is for direct tool-calling ({@link com.example.aiplatform.ai.tools.SupportToolsTest}):
 * the LLM (or here, the test itself, standing in for one) never decides
 * whether access is allowed - {@code SupportTools.requireOwnedByCaller}
 * does, reached through a completely different transport.
 *
 * Uses a fixed port ({@code webEnvironment = DEFINED_PORT}) rather than a
 * random one specifically so the MCP client's target URL can be a plain,
 * static test property - no chicken-and-egg problem of needing the assigned
 * port before the context (and therefore the port) exists.
 */
@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18099")
class McpToolCallIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @org.springframework.test.context.DynamicPropertySource
    static void redisProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @MockBean
    private LlmClientService llmClientService;

    @MockBean
    private EmbeddingService embeddingService;

    @Test
    void toolsListExposesTheFourNamedToolsOverMcp() {
        McpSyncClient client = connectAs("alice", "password");
        try {
            List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            assertThat(names).contains("getOrder", "getPaymentStatus", "getCustomer", "searchKnowledgeBase");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void callingGetOrderForOwnOrderSucceedsThroughMcp() {
        // alice is CUST-1001, and ORD-1001 belongs to CUST-1001 (BusinessDataStore fixture).
        McpSyncClient client = connectAs("alice", "password");
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("getOrder", Map.of("orderId", "ORD-1001")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(result)).contains("ORD-1001");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void callingGetOrderForAnotherCustomersOrderIsDeniedThroughMcpTheSameWayAsDirectToolCalling() {
        // bob is CUST-1002; ORD-1001 belongs to CUST-1001 - the exact
        // scenario SupportToolsTest proves is blocked when the tool is
        // called in-process. Here it's called over a real MCP connection
        // instead, and must be blocked for the identical reason.
        //
        // The denial deliberately reads as "no order found" rather than "not
        // authorized", over MCP exactly as over REST: telling the caller the
        // order exists but is not theirs is an existence oracle for order-ID
        // enumeration. The DENY is recorded in the audit log, where the
        // attacker cannot see it. See SupportTools.requireOwnedByCaller.
        McpSyncClient client = connectAs("bob", "password");
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("getOrder", Map.of("orderId", "ORD-1001")));

            assertThat(result.isError()).isTrue();
            assertThat(extractText(result))
                    .as("the denial must be indistinguishable from a genuine miss")
                    .containsIgnoringCase("No order found with ID ORD-1001")
                    .doesNotContainIgnoringCase("not authorized")
                    .doesNotContain("CUST-1002");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void supportAgentCanAccessAnyCustomersOrderThroughMcpTooViaTheSameRbacRule() {
        // carol is SUPPORT_AGENT - the staff-override rule (Phase 11) applies
        // identically whether the tool is reached in-process or over MCP,
        // because it is the same requireOwnedByCaller check either way.
        McpSyncClient client = connectAs("carol", "password");
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("getOrder", Map.of("orderId", "ORD-1001")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(result)).contains("ORD-1001");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void unauthenticatedMcpConnectionIsRejected() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:18099")
                .endpoint("/mcp")
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("test-client-no-auth", "1.0.0"))
                .build();

        try {
            assertThatThrownBy(client::initialize)
                    .satisfiesAnyOf(
                            ex -> assertThat(ex).isInstanceOf(McpTransportException.class),
                            ex -> assertThat(ex).isInstanceOf(RuntimeException.class));
        } finally {
            client.closeGracefully();
        }
    }

    private static McpSyncClient connectAs(String username, String password) {
        String credentials = username + ":" + password;
        String basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:18099")
                .endpoint("/mcp")
                .customizeRequest(requestBuilder -> requestBuilder.header("Authorization", basicAuthHeader))
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("mcp-tool-call-integration-test", "1.0.0"))
                .build();
        client.initialize();
        return client;
    }

    private static String extractText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", (a, b) -> a + b);
    }
}
