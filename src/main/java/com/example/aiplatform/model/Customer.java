package com.example.aiplatform.model;

public record Customer(
        String customerId,
        String name,
        String email,
        String tier
) {
}
