package com.example.aiplatform.model;

public record InventoryStatus(
        String productId,
        int quantityAvailable,
        boolean inStock
) {
}
