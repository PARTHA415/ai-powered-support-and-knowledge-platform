package com.example.aiplatform.observability;

import com.example.aiplatform.ai.llm.LlmCallUsage;
import com.example.aiplatform.config.ModelPricingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns token counts into money, and attributes both.
 *
 * <p>The platform already had token metrics before this class existed, and they
 * were not enough to manage cost with. Tokens are not comparable across models:
 * a planning call and a synthesis call can report identical counts and differ
 * by an order of magnitude in spend, which is the entire premise of model
 * tiering ({@link com.example.aiplatform.ai.llm.ModelTier}). A dashboard
 * showing tokens cannot show that tiering worked; one showing dollars broken
 * down by tier can, and can also show the day someone quietly points the
 * {@code fast} tier at the expensive model.
 *
 * <p>Three tag dimensions, all deliberately low-cardinality - the same
 * discipline {@link CorrelationIdFilter} documents for why a correlation ID is
 * never a metric tag:
 * <ul>
 *   <li>{@code model} - a handful of values, and the one the provider
 *       <em>reported</em>, not the one requested.</li>
 *   <li>{@code tier} - two values. This is the dimension tiering decisions are
 *       actually read from.</li>
 *   <li>{@code type} - {@code prompt}, {@code prompt_cached}, {@code completion}.
 *       Splitting cached prompt tokens out is what makes prompt caching
 *       observable at all; without it a cache hit looks identical to a cache
 *       miss.</li>
 * </ul>
 *
 * <p>Per-<em>caller</em> attribution is deliberately NOT a metric tag here.
 * Usernames are unbounded, and a tag per caller is the textbook way to melt a
 * Prometheus server. Caller attribution lives in
 * {@link com.example.aiplatform.ai.guardrails.TokenBudgetGuard}, where it is a
 * Redis counter with a TTL rather than an unbounded time series.
 */
@Component
public class CostMeter {

    private static final Logger log = LoggerFactory.getLogger(CostMeter.class);

    private static final double TOKENS_PER_PRICE_UNIT = 1_000_000.0;

    private final MeterRegistry meterRegistry;
    private final ModelPricingProperties pricing;

    /**
     * Models already reported as unpriced. A missing price is a configuration
     * gap worth telling an operator about exactly once, not on every call -
     * an unpriced model on a hot path would otherwise produce one WARN per
     * request, which is how a real signal gets trained out of people.
     */
    private final Set<String> unpricedModelsWarned = ConcurrentHashMap.newKeySet();

    public CostMeter(MeterRegistry meterRegistry, ModelPricingProperties pricing) {
        this.meterRegistry = meterRegistry;
        this.pricing = pricing;
    }

    /**
     * Records one call's tokens and its cost. Returns the computed cost in USD
     * so a caller can log or accumulate it; zero when the model has no price
     * configured.
     */
    public double record(LlmCallUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return 0.0;
        }
        String model = usage.model();
        String tier = usage.tier().name().toLowerCase(java.util.Locale.ROOT);

        countTokens(model, tier, "prompt", usage.uncachedPromptTokens());
        countTokens(model, tier, "prompt_cached", usage.cachedPromptTokens());
        countTokens(model, tier, "completion", usage.completionTokens());

        Optional<ModelPricingProperties.ModelPrice> price = pricing.priceFor(model);
        if (price.isEmpty()) {
            if (unpricedModelsWarned.add(model)) {
                log.warn("No price configured for model '{}' (app.ai.pricing.models) - its tokens are still "
                        + "counted, but its spend is reported as zero. Add an entry so cost dashboards are "
                        + "not quietly understated.", model);
            }
            return 0.0;
        }

        ModelPricingProperties.ModelPrice rates = price.get();
        double cost = usage.uncachedPromptTokens() / TOKENS_PER_PRICE_UNIT * rates.inputPerMillionTokens()
                + usage.cachedPromptTokens() / TOKENS_PER_PRICE_UNIT * rates.cachedInputPerMillionTokens()
                + usage.completionTokens() / TOKENS_PER_PRICE_UNIT * rates.outputPerMillionTokens();

        Counter.builder("ai.cost.usd")
                .description("Estimated LLM spend in USD, derived from reported token usage and configured prices")
                .tag("model", model)
                .tag("tier", tier)
                .register(meterRegistry)
                .increment(cost);

        log.debug("LLM call on {} ({} tier): {} prompt ({} cached) + {} completion tokens = ${}",
                model, tier, usage.promptTokens(), usage.cachedPromptTokens(), usage.completionTokens(), cost);
        return cost;
    }

    private void countTokens(String model, String tier, String type, long tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder("ai.tokens")
                .description("LLM tokens consumed, split by prompt/cached-prompt/completion")
                .tag("model", model)
                .tag("tier", tier)
                .tag("type", type)
                .register(meterRegistry)
                .increment(tokens);
    }
}
