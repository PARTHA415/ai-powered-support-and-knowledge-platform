package com.example.aiplatform.model;

public record ShipmentStatus(
        String orderId,
        String carrier,
        String trackingNumber,
        String status,
        String estimatedDelivery
) {
}
