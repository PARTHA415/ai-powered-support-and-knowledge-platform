package com.example.aiplatform.observability;

import com.example.aiplatform.ai.llm.LlmCallUsage;
import com.example.aiplatform.ai.llm.ModelTier;
import com.example.aiplatform.config.ModelPricingProperties;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostMeterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CostMeter meterWithPrices() {
        return new CostMeter(meterRegistry, new ModelPricingProperties(Map.of(
                "gpt-4o", new ModelPricingProperties.ModelPrice(2.5, 10.0, 1.25),
                "gpt-4o-mini", new ModelPricingProperties.ModelPrice(0.15, 0.6, 0.075))));
    }

    @Test
    void costIsPromptPlusCompletionAtTheConfiguredRates() {
        double cost = meterWithPrices().record(
                new LlmCallUsage("gpt-4o", ModelTier.CAPABLE, 1_000_000, 0, 500_000));

        // 1M prompt at $2.50 + 0.5M completion at $10.00
        assertThat(cost).isEqualTo(2.5 + 5.0);
        assertThat(meterRegistry.get("ai.cost.usd").tag("model", "gpt-4o").tag("tier", "capable")
                .counter().count()).isEqualTo(7.5);
    }

    /**
     * Cached prompt tokens are billed at a different rate, and splitting them
     * out is the only thing that makes prompt caching observable - a cache hit
     * would otherwise look identical to a miss.
     */
    @Test
    void cachedPromptTokensAreBilledSeparatelyAndCountedSeparately() {
        double cost = meterWithPrices().record(
                new LlmCallUsage("gpt-4o", ModelTier.CAPABLE, 1_000_000, 800_000, 0));

        // 200k uncached at $2.50/M + 800k cached at $1.25/M
        assertThat(cost).isEqualTo(0.5 + 1.0);
        assertThat(meterRegistry.get("ai.tokens").tag("type", "prompt").counter().count()).isEqualTo(200_000.0);
        assertThat(meterRegistry.get("ai.tokens").tag("type", "prompt_cached").counter().count())
                .isEqualTo(800_000.0);
    }

    /**
     * The tier tag is what makes model tiering provable. Without it a dashboard
     * shows total spend and cannot say whether the cheap model is being used
     * where it was supposed to be.
     */
    @Test
    void spendIsAttributedToTheTierThatIncurredIt() {
        CostMeter costMeter = meterWithPrices();

        costMeter.record(new LlmCallUsage("gpt-4o-mini", ModelTier.FAST, 1_000_000, 0, 0));
        costMeter.record(new LlmCallUsage("gpt-4o", ModelTier.CAPABLE, 1_000_000, 0, 0));

        assertThat(meterRegistry.get("ai.cost.usd").tag("tier", "fast").counter().count()).isEqualTo(0.15);
        assertThat(meterRegistry.get("ai.cost.usd").tag("tier", "capable").counter().count()).isEqualTo(2.5);
    }

    /**
     * A dated snapshot the provider reports is priced by its family entry, so
     * every snapshot does not need its own configuration line.
     */
    @Test
    void aDatedModelSnapshotIsPricedByItsFamilyPrefix() {
        double cost = meterWithPrices().record(
                new LlmCallUsage("gpt-4o-mini-2024-07-18", ModelTier.FAST, 1_000_000, 0, 0));

        assertThat(cost).isEqualTo(0.15);
    }

    /**
     * An unpriced model still has its tokens counted. Reporting an invented
     * price would be worse than reporting none - the count is real either way,
     * only the money is unclaimed.
     */
    @Test
    void anUnpricedModelIsStillCountedButContributesNoSpend() {
        CostMeter costMeter = new CostMeter(meterRegistry, new ModelPricingProperties(Map.of()));

        double cost = costMeter.record(new LlmCallUsage("some-other-model", ModelTier.CAPABLE, 1000, 0, 500));

        assertThat(cost).isEqualTo(0.0);
        assertThat(meterRegistry.get("ai.tokens").tag("type", "prompt").counter().count()).isEqualTo(1000.0);
        assertThatThrownBy(() -> meterRegistry.get("ai.cost.usd").counter())
                .isInstanceOf(MeterNotFoundException.class);
    }

    @Test
    void aCallThatReportedNoUsageRecordsNothing() {
        assertThat(meterWithPrices().record(LlmCallUsage.none(ModelTier.CAPABLE))).isEqualTo(0.0);
        assertThatThrownBy(() -> meterRegistry.get("ai.tokens").counter())
                .isInstanceOf(MeterNotFoundException.class);
    }
}
