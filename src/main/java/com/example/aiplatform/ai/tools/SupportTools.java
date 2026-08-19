package com.example.aiplatform.ai.tools;

import com.example.aiplatform.exception.InvalidToolArgumentException;
import com.example.aiplatform.exception.ToolResourceNotFoundException;
import com.example.aiplatform.exception.UnauthorizedToolAccessException;
import com.example.aiplatform.model.Customer;
import com.example.aiplatform.model.InventoryStatus;
import com.example.aiplatform.model.Order;
import com.example.aiplatform.model.PaymentStatus;
import com.example.aiplatform.model.ShipmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

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
 * {@link CallerContextHolder} - the LLM choosing to ask for a given order ID
 * does not by itself authorize access to it.
 */
@Component
public class SupportTools {

    private static final Logger log = LoggerFactory.getLogger(SupportTools.class);

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^ORD-\\d{4,}$");
    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("^CUST-\\d{4,}$");
    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("^PROD-\\d{4,}$");

    private final BusinessDataStore businessDataStore;

    public SupportTools(BusinessDataStore businessDataStore) {
        this.businessDataStore = businessDataStore;
    }

    @Tool(description = "Get an order's status, total amount, and order date by order ID")
    public Order getOrder(@ToolParam(description = "The order ID, formatted like ORD-1001") String orderId) {
        validateOrderId(orderId);
        Order order = businessDataStore.findOrder(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No order found with ID " + orderId));
        requireOwnedByCaller(order.customerId(), "order " + orderId);
        log.debug("getOrder({}) -> {}", orderId, order.status());
        return order;
    }

    @Tool(description = "Get the payment status and amount paid for an order by order ID")
    public PaymentStatus getPaymentStatus(
            @ToolParam(description = "The order ID, formatted like ORD-1001") String orderId) {
        validateOrderId(orderId);
        Order order = businessDataStore.findOrder(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No order found with ID " + orderId));
        requireOwnedByCaller(order.customerId(), "order " + orderId);
        PaymentStatus paymentStatus = businessDataStore.findPaymentStatus(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No payment record found for order " + orderId));
        log.debug("getPaymentStatus({}) -> {}", orderId, paymentStatus.status());
        return paymentStatus;
    }

    @Tool(description = "Get shipment carrier, tracking number, and delivery status for an order by order ID")
    public ShipmentStatus getShipmentStatus(
            @ToolParam(description = "The order ID, formatted like ORD-1001") String orderId) {
        validateOrderId(orderId);
        Order order = businessDataStore.findOrder(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No order found with ID " + orderId));
        requireOwnedByCaller(order.customerId(), "order " + orderId);
        ShipmentStatus shipmentStatus = businessDataStore.findShipmentStatus(orderId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No shipment found for order " + orderId));
        log.debug("getShipmentStatus({}) -> {}", orderId, shipmentStatus.status());
        return shipmentStatus;
    }

    @Tool(description = "Get a customer's profile (name, email, membership tier) by customer ID")
    public Customer getCustomer(
            @ToolParam(description = "The customer ID, formatted like CUST-1001") String customerId) {
        validateCustomerId(customerId);
        requireOwnedByCaller(customerId, "customer profile " + customerId);
        Customer customer = businessDataStore.findCustomer(customerId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No customer found with ID " + customerId));
        log.debug("getCustomer({}) -> {}", customerId, customer.tier());
        return customer;
    }

    @Tool(description = "Check how many units of a product are currently in stock by product ID")
    public InventoryStatus checkInventory(
            @ToolParam(description = "The product ID, formatted like PROD-2001") String productId) {
        validateProductId(productId);
        // Deliberately no caller-ownership check: inventory is general
        // product data, not scoped to any one customer.
        InventoryStatus inventoryStatus = businessDataStore.findInventory(productId)
                .orElseThrow(() -> new ToolResourceNotFoundException("No product found with ID " + productId));
        log.debug("checkInventory({}) -> {} units", productId, inventoryStatus.quantityAvailable());
        return inventoryStatus;
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

    private static void requireOwnedByCaller(String ownerCustomerId, String resourceDescription) {
        String callerCustomerId = CallerContextHolder.getCurrentCustomerId();
        if (!callerCustomerId.equals(ownerCustomerId)) {
            throw new UnauthorizedToolAccessException(
                    "Caller " + callerCustomerId + " is not authorized to access " + resourceDescription);
        }
    }
}
