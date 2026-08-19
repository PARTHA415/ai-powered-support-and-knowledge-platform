package com.example.aiplatform.ai.tools;

import com.example.aiplatform.model.Customer;
import com.example.aiplatform.model.InventoryStatus;
import com.example.aiplatform.model.Order;
import com.example.aiplatform.model.PaymentStatus;
import com.example.aiplatform.model.ShipmentStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * A fixed, deterministic in-memory fixture standing in for real order/
 * payment/shipment/customer/inventory systems. This is intentionally NOT a
 * database - the point of this phase is the tool-calling boundary itself
 * (schema, selection, validated arguments, structured results), which holds
 * identically whether the lookup below hits a HashMap or a real repository
 * backed by Postgres. Swapping this class for one backed by JPA repositories
 * later would not change anything about how {@link SupportTools} is called
 * or how the LLM interacts with it - the LLM never sees this class either way.
 */
@Component
class BusinessDataStore {

    private final Map<String, Order> orders = Map.of(
            "ORD-1001", new Order("ORD-1001", "CUST-1001", "SHIPPED", 129.99, "2026-08-01"),
            "ORD-1002", new Order("ORD-1002", "CUST-1002", "PROCESSING", 59.50, "2026-08-10"));

    private final Map<String, PaymentStatus> payments = Map.of(
            "ORD-1001", new PaymentStatus("ORD-1001", "PAID", 129.99, "CREDIT_CARD"),
            "ORD-1002", new PaymentStatus("ORD-1002", "PENDING", 0.0, "PAYPAL"));

    private final Map<String, ShipmentStatus> shipments = Map.of(
            "ORD-1001", new ShipmentStatus("ORD-1001", "FedEx", "FX123456789", "IN_TRANSIT", "2026-08-22"));

    private final Map<String, Customer> customers = Map.of(
            "CUST-1001", new Customer("CUST-1001", "Alice Johnson", "alice@example.com", "GOLD"),
            "CUST-1002", new Customer("CUST-1002", "Bob Smith", "bob@example.com", "STANDARD"));

    private final Map<String, InventoryStatus> inventory = Map.of(
            "PROD-2001", new InventoryStatus("PROD-2001", 42, true),
            "PROD-2002", new InventoryStatus("PROD-2002", 0, false));

    Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    Optional<PaymentStatus> findPaymentStatus(String orderId) {
        return Optional.ofNullable(payments.get(orderId));
    }

    Optional<ShipmentStatus> findShipmentStatus(String orderId) {
        return Optional.ofNullable(shipments.get(orderId));
    }

    Optional<Customer> findCustomer(String customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }

    Optional<InventoryStatus> findInventory(String productId) {
        return Optional.ofNullable(inventory.get(productId));
    }
}
