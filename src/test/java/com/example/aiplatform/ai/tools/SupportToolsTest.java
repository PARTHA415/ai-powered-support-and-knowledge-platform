package com.example.aiplatform.ai.tools;

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
 */
class SupportToolsTest {

    private final BusinessDataStore businessDataStore = new BusinessDataStore();
    private final SupportTools supportTools = new SupportTools(businessDataStore);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
            .toolObjects(supportTools)
            .build()
            .getToolCallbacks();

    @AfterEach
    void clearCallerContext() {
        CallerContextHolder.clear();
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
        CallerContextHolder.setCurrentCustomerId("CUST-1001");

        JsonNode result = callTool("getOrder", "{\"orderId\":\"ORD-1001\"}");

        assertThat(result.get("orderId").asText()).isEqualTo("ORD-1001");
        assertThat(result.get("status").asText()).isEqualTo("SHIPPED");
    }

    @Test
    void checkInventoryWorksWithoutAnyCallerContext() throws Exception {
        // No CallerContextHolder set at all - inventory isn't customer-scoped.
        JsonNode result = callTool("checkInventory", "{\"productId\":\"PROD-2001\"}");

        assertThat(result.get("quantityAvailable").asInt()).isEqualTo(42);
        assertThat(result.get("inStock").asBoolean()).isTrue();
    }

    // --- invalid arguments ---

    @Test
    void getOrderWithMalformedOrderIdIsRejected() {
        CallerContextHolder.setCurrentCustomerId("CUST-1001");
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
        CallerContextHolder.setCurrentCustomerId("CUST-1001");
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
        CallerContextHolder.setCurrentCustomerId("CUST-1002");
        ToolCallback getShipmentStatus = findTool("getShipmentStatus");

        assertThatThrownBy(() -> getShipmentStatus.call("{\"orderId\":\"ORD-1002\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.ToolResourceNotFoundException.class);
    }

    // --- unauthorized tool access ---

    @Test
    void getOrderForAnotherCustomersOrderIsUnauthorized() {
        // ORD-1001 belongs to CUST-1001; CUST-1002 must not be able to read it,
        // even though the tool call itself is well-formed and the order exists.
        CallerContextHolder.setCurrentCustomerId("CUST-1002");
        ToolCallback getOrder = findTool("getOrder");

        assertThatThrownBy(() -> getOrder.call("{\"orderId\":\"ORD-1001\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.UnauthorizedToolAccessException.class);
    }

    @Test
    void getCustomerForAnotherCustomersProfileIsUnauthorized() {
        CallerContextHolder.setCurrentCustomerId("CUST-1002");
        ToolCallback getCustomer = findTool("getCustomer");

        assertThatThrownBy(() -> getCustomer.call("{\"customerId\":\"CUST-1001\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .cause()
                .isInstanceOf(com.example.aiplatform.exception.UnauthorizedToolAccessException.class);
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
