package com.example.aiplatform.model;

public record PaymentStatus(
        String orderId,
        String status,
        double amountPaid,
        String paymentMethod
) {
}
