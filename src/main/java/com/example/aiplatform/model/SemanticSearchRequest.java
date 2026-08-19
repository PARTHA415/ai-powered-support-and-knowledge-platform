package com.example.aiplatform.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SemanticSearchRequest(
        @NotBlank(message = "query must not be blank")
        String query,
        @Min(1) @Max(50)
        Integer limit
) {
    public int effectiveLimit() {
        return limit == null ? 5 : limit;
    }
}
