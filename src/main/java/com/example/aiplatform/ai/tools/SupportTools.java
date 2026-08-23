package com.example.aiplatform.ai.tools;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.guardrails.ToolExecutionGuard;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.exception.InvalidToolArgumentException;
import com.example.aiplatform.exception.ToolResourceNotFoundException;
import com.example.aiplatform.exception.UnauthorizedToolAccessException;
import com.example.aiplatform.model.Customer;
import com.example.aiplatform.model.InventoryStatus;
import com.example.aiplatform.model.Order;
import com.example.aiplatform.model.PaymentStatus;
import com.example.aiplatform.model.Role;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.model.ShipmentStatus;
import com.example.aiplatform.security.AuditLogger;
import com.example.aiplatform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The only surface an LLM can use to reach application data in this phase.
 * Each method here is a deterministic Java function - same input, same
 * output, no model involved in computing the result - that the LLM can
 * request by name with JSON arguments. It never sees {@link BusinessDataStore},
 * never sees SQL, never gets a connection: it gets a tool name, a schema, and
 * whatever record these methods choose to return. That boundary is what
 * "the LLM must never directly access the database" means in practice.
 *
 * Every order/payment/shipment-scoped lookup is additionally gated by
 * {@link CurrentUser}, which reads the REAL authenticated caller from Spring
 * Security's SecurityContext (Phase 11) - not an identity the LLM could
 * influence, and not anything the tool's own JSON arguments carry. The LLM
 * choosing to ask for a given order ID does not by itself authorize access
 * to it: a USER-role caller may only access their own customerId's data;
 * SUPPORT_AGENT and ADMIN callers - real staff, verified by authentication,
 * never by anything the model claims - may access any customer's data, the
 * same way a real support agent legitimately can when helping a customer.
 *
 * As of Phase 13, this same instance is also registered as an MCP
 * {@code ToolCallbackProvider} bean ({@code config/McpServerConfig}) and
 * exposed over this application's MCP server - a second transport for the
 * exact same methods and the exact same {@link CurrentUser}-based
 * authorization, not a second implementation of either.
 */
@Component
public class SupportTools {

    private static final Logger log = LoggerFactory.getLogger(SupportTools.class);

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^ORD-\\d{4,}$");
    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("^CUST-\\d{4,}$");
    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("^PROD-\\d{4,}$");

    private final BusinessDataStore businessDataStore;
    private final ToolExecutionGuard toolExecutionGuard;
    private final SemanticSearchService semanticSearchService;
    private final RagProperties ragProperties;
    private final PromptInjectionGuard promptInjectionGuard;

    public SupportTools(BusinessDataStore businessDataStore,
                         ToolExecutionGuard toolExecutionGuard,
                         SemanticSearchService semanticSearchService,
                         RagProperties ragProperties,
                         PromptInjectionGuard promptInjectionGuard) {
        this.businessDataStore = businessDataStore;
        this.toolExecutionGuard = toolExecutionGuard;
        this.semanticSearchService = semanticSearchService;
        this.ragProperties = ragProperties;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    @Tool(description = "Get an order's status, total amount, and order date by order ID")
    public Order getOrder(@ToolParam(description = "The order ID, formatted like ORD-1001") String orderId) {
        toolExecutionGuard.recordInvocation("getOrder");
        validateOrderId(orderId);
        Order order = businessDataStore.findOrder(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException(orderNotFoundMessage(orderId)));
        requireOwnedByCaller(order.customerId(), "order " + orderId, orderNotFoundMessage(orderId));
        log.debug("getOrder({}) -> {}", orderId, order.status());
        return order;
    }

    @Tool(description = "Get the payment status and amount paid for an order by order ID")
    public PaymentStatus getPaymentStatus(
            @ToolParam(description = "The order ID, formatted like ORD-1001") String orderId) {
        toolExecutionGuard.recordInvocation("getPaymentStatus");
        validateOrderId(orderId);
        Order order = businessDataStore.findOrder(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException(orderNotFoundMessage(orderId)));
        requireOwnedByCaller(order.customerId(), "order " + orderId, orderNotFoundMessage(orderId));
        PaymentStatus paymentStatus = businessDataStore.findPaymentStatus(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No payment record found for order " + orderId));
        log.debug("getPaymentStatus({}) -> {}", orderId, paymentStatus.status());
        return paymentStatus;
    }

    @Tool(description = "Get shipment carrier, tracking number, and delivery status for an order by order ID")
    public ShipmentStatus getShipmentStatus(
            @ToolParam(description = "The order ID, formatted like ORD-1001") String orderId) {
        toolExecutionGuard.recordInvocation("getShipmentStatus");
        validateOrderId(orderId);
        Order order = businessDataStore.findOrder(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException(orderNotFoundMessage(orderId)));
        requireOwnedByCaller(order.customerId(), "order " + orderId, orderNotFoundMessage(orderId));
        ShipmentStatus shipmentStatus = businessDataStore.findShipmentStatus(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No shipment found for order " + orderId));
        log.debug("getShipmentStatus({}) -> {}", orderId, shipmentStatus.status());
        return shipmentStatus;
    }

    @Tool(description = "Get a customer's profile (name, email, membership tier) by customer ID")
    public Customer getCustomer(
            @ToolParam(description = "The customer ID, formatted like CUST-1001") String customerId) {
        toolExecutionGuard.recordInvocation("getCustomer");
        validateCustomerId(customerId);
        requireOwnedByCaller(customerId, "customer profile " + customerId, customerNotFoundMessage(customerId));
        Customer customer = businessDataStore.findCustomer(customerId)
                .orElseThrow(() -> new ToolResourceNotFoundException(customerNotFoundMessage(customerId)));
        log.debug("getCustomer({}) -> {}", customerId, customer.tier());
        return customer;
    }

    @Tool(description = "Check how many units of a product are currently in stock by product ID")
    public InventoryStatus checkInventory(
            @ToolParam(description = "The product ID, formatted like PROD-2001") String productId) {
        toolExecutionGuard.recordInvocation("checkInventory");
        validateProductId(productId);
        // Deliberately no caller-ownership check: inventory is general
        // product data, not scoped to any one customer.
        InventoryStatus inventoryStatus = businessDataStore.findInventory(productId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No product found with ID " + productId));
        log.debug("checkInventory({}) -> {} units", productId, inventoryStatus.quantityAvailable());
        return inventoryStatus;
    }

    @Tool(description = "Search the internal knowledge base for documentation relevant to a support or "
            + "troubleshooting question")
    public List<SemanticSearchResult> searchKnowledgeBase(
            @ToolParam(description = "The search query, e.g. a support question or a topic to look up")
            String query) {
        toolExecutionGuard.recordInvocation("searchKnowledgeBase");
        // Deliberately no caller-ownership check, like checkInventory: the
        // knowledge base is general documentation, not scoped to any one
        // customer (see the Phase 11 docs on why per-document ACLs were
        // deferred). Each chunk is still sanitized before it reaches the
        // model - the same indirect-injection defense QuestionAnsweringService
        // and AgentService apply, because this is the same retrieved,
        // untrusted content reaching the LLM through one more path.
        List<SemanticSearchResult> results = semanticSearchService.search(query, ragProperties.topK());
        List<SemanticSearchResult> sanitized = results.stream()
                .map(result -> new SemanticSearchResult(
                        result.documentTitle(), promptInjectionGuard.sanitize(result.content()), result.similarity()))
                .toList();
        // Logs the query LENGTH, never its content - a support question can
        // easily contain a name, an email address, or other PII typed by
        // the caller (Phase 14's "never log sensitive prompts" rule).
        log.debug("searchKnowledgeBase(query.length={}) -> {} result(s)", query.length(), sanitized.size());
        return sanitized;
    }

    /**
     * The single definition of "no such order", used both for a genuine miss
     * and for an ownership denial. They must stay byte-identical or the
     * existence oracle {@link #requireOwnedByCaller} closes reopens - two
     * separately-maintained string literals would drift apart eventually, so
     * there is only one.
     */
    private static String orderNotFoundMessage(String orderId) {
        return "No order found with ID " + orderId;
    }

    /** Counterpart to {@link #orderNotFoundMessage} for customer lookups. */
    private static String customerNotFoundMessage(String customerId) {
        return "No customer found with ID " + customerId;
    }

    private static void validateOrderId(String orderId) {
        if (orderId == null || !ORDER_ID_PATTERN.matcher(orderId).matches()) {
            throw new InvalidToolArgumentException(
                    "orderId must match the pattern ORD-#### (got: " + orderId + ")");
        }
    }

    private static void validateCustomerId(String customerId) {
        if (customerId == null || !CUSTOMER_ID_PATTERN.matcher(customerId).matches()) {
            throw new InvalidToolArgumentException(
                    "customerId must match the pattern CUST-#### (got: " + customerId + ")");
        }
    }

    private static void validateProductId(String productId) {
        if (productId == null || !PRODUCT_ID_PATTERN.matcher(productId).matches()) {
            throw new InvalidToolArgumentException(
                    "productId must match the pattern PROD-#### (got: " + productId + ")");
        }
    }

    /**
     * Enforces per-resource ownership, and does it without telling the caller
     * whether the resource exists.
     *
     * <p>{@code notFoundMessage} is deliberately identical to the message a
     * genuine miss produces, and {@link UnauthorizedToolAccessException} is
     * deliberately mapped to 404 rather than 403 (see
     * {@link com.example.aiplatform.exception.GlobalExceptionHandler}).
     * Distinguishing "this order does not exist" from "this order exists but
     * is not yours" is an existence oracle: a customer could walk the order-ID
     * space and learn exactly which IDs are real from the status code alone.
     * Order IDs are short and sequential, so that is a practical enumeration,
     * not a theoretical one. Answering identically in both cases is the same
     * reason a private repository on a code-hosting site 404s rather than
     * 403s for someone without access.
     *
     * <p>The distinction is not lost, it is only moved somewhere the attacker
     * cannot see: {@link AuditLogger} records a DENY with the real caller and
     * the real resource, and the exception type stays distinct so denials
     * remain separable in logs and metrics from ordinary misses.
     *
     * <p>The null check on the caller's customer ID is a fail-closed default,
     * not defensive noise. The schema permits a USER row with a NULL
     * customer_id, and the previous {@code callerCustomerId.equals(...)} threw
     * a NullPointerException for such an account - surfacing as a 500 from a
     * code path whose entire job is to produce a clean denial.
     */
    private static void requireOwnedByCaller(String ownerCustomerId, String resourceDescription,
                                              String notFoundMessage) {
        Role callerRole = CurrentUser.role();
        if (callerRole == Role.SUPPORT_AGENT || callerRole == Role.ADMIN) {
            // Staff can act on behalf of any customer - this is a role check,
            // not a bypass: it still requires a real, authenticated staff
            // account, verified by Spring Security, never by the LLM.
            AuditLogger.logToolAccess(resourceDescription, true, "staff:" + callerRole);
            return;
        }
        String callerCustomerId = CurrentUser.customerId();
        if (callerCustomerId == null || !callerCustomerId.equals(ownerCustomerId)) {
            AuditLogger.logToolAccess(resourceDescription, false,
                    callerCustomerId == null ? "user-without-customer-id" : callerCustomerId);
            throw new UnauthorizedToolAccessException(notFoundMessage);
        }
        AuditLogger.logToolAccess(resourceDescription, true, callerCustomerId);
    }
}
