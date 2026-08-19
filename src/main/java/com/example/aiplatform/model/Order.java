package com.example.aiplatform.model;

public record Order(
        String orderId,
        String customerId,
        String status,
        double totalAmount,
        String orderDate
) {
}
