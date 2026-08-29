package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Token prices per model, in USD per million tokens - the lookup table that
 * turns a token count into money.
 *
 * <p>The review's lowest-scoring dimension was cost optimization, and the
 * reason was concrete: the platform already recorded token counts (Spring AI's
 * {@code gen_ai.client.token.usage}) but nothing anywhere converted them to
 * spend. A token count is not a cost signal an operator can act on - it does
 * not distinguish a million cheap planning tokens from fifty thousand expensive
 * synthesis ones, which is precisely the distinction model tiering exists to
 * create. Without a price table, tiering cannot be shown to have worked.
 *
 * <p>Prices live in configuration rather than in code because they change
 * without warning and are not the application's business. A model with no entry
 * here is metered at zero and logged once - the count is still recorded, the
 * money simply is not claimed, which is the honest failure mode. Inventing a
 * price would be worse than reporting none.
 *
 * <p>Map keys are matched case-insensitively against the model name the
 * <em>provider reported</em> in the response metadata, not the name that was
 * requested. Those differ in practice (an alias resolving to a dated snapshot),
 * and the reported one is what was actually billed.
 */
@ConfigurationProperties(prefix = "app.ai.pricing")
public record ModelPricingProperties(Map<String, ModelPrice> models) {

    public ModelPricingProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    /**
     * @param inputPerMillionTokens       prompt tokens billed at the full rate
     * @param outputPerMillionTokens      completion tokens
     * @param cachedInputPerMillionTokens prompt tokens the provider served from
     *                                    its own prefix cache. Typically a
     *                                    fraction of the input rate, and the
     *                                    reason prompt caching is worth
     *                                    arranging for at all - see
     *                                    {@link com.example.aiplatform.ai.prompt.SupportPromptBuilder}.
     */
    public record ModelPrice(
            @DefaultValue("0") double inputPerMillionTokens,
            @DefaultValue("0") double outputPerMillionTokens,
            @DefaultValue("0") double cachedInputPerMillionTokens
    ) {
    }

    /**
     * Longest-prefix match, so a dated snapshot the provider reports
     * ({@code gpt-4o-mini-2024-07-18}) is priced by its family entry
     * ({@code gpt-4o-mini}) without every snapshot needing its own line. An
     * exact entry always wins over a prefix.
     */
    public Optional<ModelPrice> priceFor(String reportedModel) {
        if (reportedModel == null || reportedModel.isBlank() || models.isEmpty()) {
            return Optional.empty();
        }
        String normalized = reportedModel.toLowerCase(Locale.ROOT);
        ModelPrice exact = models.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        return models.entrySet().stream()
                .filter(entry -> normalized.startsWith(entry.getKey().toLowerCase(Locale.ROOT)))
                .max(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(String::length)))
                .map(Map.Entry::getValue);
    }
}
