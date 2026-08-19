package com.example.aiplatform.model;

public record EmbedResponse(
        String model,
        int dimensions,
        float[] vector
) {
}
