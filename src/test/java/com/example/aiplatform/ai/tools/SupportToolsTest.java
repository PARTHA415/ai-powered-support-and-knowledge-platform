package com.example.aiplatform.ai.tools;

import com.example.aiplatform.ai.guardrails.ToolExecutionGuard;
import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.model.Role;
import com.example.aiplatform.security.TestPrincipals;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the actual Spring AI tool-calling machinery - JSON argument
 * parsing, reflective method invocation, and exception wrapping - directly
 * against {@link SupportTools}, via {@link ToolCallback#call(String)}. This
 * is the deterministic, testable part of "tool calling": given a tool name
 * and JSON arguments (which is exactly what an LLM produces when it selects
 * a tool), does our system execute it correctly? Whether a real LLM chooses
 * to call the right tool for a given question is model judgment, not
 * something this test suite controls or asserts on - what's verified here is
 * that the tools are correctly registered and described (so an LLM *can*
 * select correctly) and that execution is correct once a tool is selected.
 *
 * As of Phase 11, the caller identity comes from a fake Spring Security
 * SecurityContext ({@link TestPrincipals}) rather than a manually-set
 * ThreadLocal - the same mechanism the real application uses after HTTP
 * Basic authentication succeeds.
 */
class SupportToolsTest {

    private final BusinessDataStore businessDataStore = new BusinessDataStore();
    private final SupportTools supportTools =
            new SupportTools(businessDataStore, new ToolExecutionGuard(new GuardrailProperties(20, 6000)));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
            .toolObjects(supportTools)
            .build()
            .getToolCallbacks();

    @AfterEach
    void clearSecurityContext() {
        TestPrincipals.clear();
    }

    // --- tool registration / schema correctness (what makes correct LLM selection possible) ---

    @Test
    void allFiveToolsAreRegisteredWithNonBlankDescriptionsAndSchemas() {
        Set<String> names = Arrays.stream(toolCallbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "getOrder", "getPaymentStatus", "getShipmentStatus", "getCustomer", "checkInventory");

        for (ToolCallback callback : toolCallbacks) {
            assertThat(callback.getToolDefinition().description()).isNotBlank();
            assertThat(callback.getToolDefinition().inputSchema()).isNotBlank();
        }
    }

    @Test
    void getOrderSchemaDeclaresOrderIdParameter() {
        ToolCallback getOrder = findTool("getOrder");

        assertThat(getOrder.getToolDefinition().inputSchema()).contains("orderId");
    }

    // --- correct tool execution given valid arguments ---

    @Test
    void getOrderReturnsOrderForOwningCaller() throws Exception {
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1001"));

        JsonNode result = callTool("getOrder", "{\"orderId\":\"ORD-1001\"}");

        assertThat(result.get("orderId").asText()).isEqualTo("ORD-1001");
        assertThat(result.get("status").asText()).isEqualTo("SHIPPED");
    }

    @Test
    void checkInventoryWorksWithoutAnyCallerContext() throws Exception {
        // No authentication set up at all - inventory isn't customer-scoped.
        JsonNode result = callTool("checkInventory", "{\"productId\":\"PROD-2001\"}");

        assertThat(result.get("quantityAvailable").asInt()).isEqualTo(42);
        assertThat(result.get("inStock").asBoolean()).isTrue();
    }

    // --- invalid arguments ---

    @Test
    void getOrderWithMalformedOrderIdIsRejected() {
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1001"));
        ToolCallback getOrder = findTool("getOrder");

        assertThatThrownBy(() -> getOrder.call("{\"orderId\":\"not-an-order-id\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.InvalidToolArgumentException.class);
    }

    @Test
    void checkInventoryWithBlankProductIdIsRejected() {
        ToolCallback checkInventory = findTool("checkInventory");

        assertThatThrownBy(() -> checkInventory.call("{\"productId\":\"\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.InvalidToolArgumentException.class);
    }

    // --- tool failure (well-formed but nonexistent) ---

    @Test
    void getOrderWithNonexistentOrderIdFails() {
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1001"));
        ToolCallback getOrder = findTool("getOrder");

        assertThatThrownBy(() -> getOrder.call("{\"orderId\":\"ORD-9999\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.ToolResourceNotFoundException.class);
    }

    @Test
    void getShipmentStatusForOrderWithNoShipmentYetFails() {
        // ORD-1002 exists and belongs to CUST-1002, but has no shipment record
        // in the fixture data - a "not found" one level deeper than the order itself.
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1002"));
        ToolCallback getShipmentStatus = findTool("getShipmentStatus");

        assertThatThrownBy(() -> getShipmentStatus.call("{\"orderId\":\"ORD-1002\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.ToolResourceNotFoundException.class);
    }

    // --- unauthorized tool access: the required demonstration ---
    //
    // In every case below, the ToolCallback is invoked exactly the way Spring
    // AI invokes it when a real LLM decides to call this tool with these
    // arguments - the LLM's "request" is fully honored at the protocol level
    // (valid tool name, valid JSON arguments, the method genuinely runs).
    // What blocks it is entirely inside requireOwnedByCaller: a Java
    // comparison between the authenticated caller's identity (read from
    // Spring Security's SecurityContext, never from the tool call's own
    // arguments) and the resource's actual owner. The LLM is never asked
    // "is this allowed" and has no path to influence the answer.

    @Test
    void getOrderForAnotherCustomersOrderIsUnauthorized() {
        // ORD-1001 belongs to CUST-1001; CUST-1002 must not be able to read it,
        // even though the tool call itself is well-formed and the order exists -
        // i.e. even though the LLM's request was entirely legitimate-looking.
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1002"));
        ToolCallback getOrder = findTool("getOrder");

        assertThatThrownBy(() -> getOrder.call("{\"orderId\":\"ORD-1001\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.UnauthorizedToolAccessException.class);
    }

    @Test
    void getCustomerForAnotherCustomersProfileIsUnauthorized() {
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1002"));
        ToolCallback getCustomer = findTool("getCustomer");

        assertThatThrownBy(() -> getCustomer.call("{\"customerId\":\"CUST-1001\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.UnauthorizedToolAccessException.class);
    }

    // --- RBAC: staff roles can act on behalf of any customer ---

    @Test
    void supportAgentCanAccessAnyCustomersOrder() throws Exception {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.SUPPORT_AGENT));

        JsonNode result = callTool("getOrder", "{\"orderId\":\"ORD-1001\"}");

        assertThat(result.get("orderId").asText()).isEqualTo("ORD-1001");
    }

    @Test
    void adminCanAccessAnyCustomersProfile() throws Exception {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.ADMIN));

        JsonNode result = callTool("getCustomer", "{\"customerId\":\"CUST-1002\"}");

        assertThat(result.get("customerId").asText()).isEqualTo("CUST-1002");
    }

    // --- Phase 12: maximum tool execution guardrail ---
    //
    // A separate SupportTools/ToolExecutionGuard pair with a deliberately low
    // limit, so the third call in this test - still well-formed, still
    // authorized - is refused purely on call-count, independent of every
    // other check above. Demonstrates the defense against a model stuck
    // calling tools instead of ever producing a final answer.

    @Test
    void thirdToolCallInOneRequestIsRefusedOnceTheConfiguredLimitIsExceeded() throws Exception {
        SupportTools limitedTools = new SupportTools(businessDataStore, new ToolExecutionGuard(new GuardrailProperties(2, 6000)));
        ToolCallback checkInventory = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(limitedTools).build().getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("checkInventory"))
                .findFirst()
                .orElseThrow();

        checkInventory.call("{\"productId\":\"PROD-2001\"}");
        checkInventory.call("{\"productId\":\"PROD-2001\"}");

        assertThatThrownBy(() -> checkInventory.call("{\"productId\":\"PROD-2001\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.ToolExecutionLimitExceededException.class);
    }

    private ToolCallback findTool(String name) {
        return Arrays.stream(toolCallbacks)
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool registered with name " + name));
    }

    private JsonNode callTool(String name, String argumentsJson) throws Exception {
        String resultJson = findTool(name).call(argumentsJson);
        return objectMapper.readTree(resultJson);
    }
}
