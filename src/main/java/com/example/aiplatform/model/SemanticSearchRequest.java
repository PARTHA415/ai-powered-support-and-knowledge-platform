package com.example.aiplatform.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record SemanticSearchRequest(
        @NotBlank(message = "query must not be blank")
        String query,
        @Min(1) @Max(50)
        Integer limit,
        /**
         * Optional key/value pairs the document's metadata must contain.
         * Applied in SQL before ranking, so the caller still gets up to
         * {@code limit} matching results rather than whatever survives
         * post-filtering an unfiltered top-K.
         */
        @Size(max = 20, message = "metadataFilter must not contain more than 20 entries")
        Map<String, String> metadataFilter
) {
    public int effectiveLimit() {
        return limit == null ? 5 : limit;
    }

    public Map<String, String> effectiveMetadataFilter() {
        return metadataFilter == null ? Map.of() : metadataFilter;
    }
}
