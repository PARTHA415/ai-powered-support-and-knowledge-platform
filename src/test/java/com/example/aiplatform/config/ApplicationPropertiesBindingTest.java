package com.example.aiplatform.config;

import com.example.aiplatform.ai.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real {@code application.yml} to the real
 * {@code @ConfigurationProperties} records, without Docker, a database, or a
 * provider key.
 *
 * <h2>Why this exists</h2>
 *
 * A property name that does not match its record component does not fail
 * loudly - it binds to the default and the application runs, quietly, with the
 * wrong value. The failure mode is specific and expensive: {@code fusion-k}
 * mistyped means fusion silently uses 60 when the file says something else;
 * {@code hybrid-enabled} mistyped means the lexical search is never
 * consulted and answers just get worse. Nothing throws, nothing logs, and the
 * only symptom is quality.
 *
 * <p>The integration tests would eventually catch some of this, but they need
 * Docker. This runs in milliseconds on any machine, which is what makes it the
 * right place for a check whose whole value is that it always runs.
 */
class ApplicationPropertiesBindingTest {

    private final Binder binder = binderForApplicationYml();

    private static Binder binderForApplicationYml() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            StandardEnvironment environment = new StandardEnvironment();
            // The first document is the default profile; the second is the dev
            // profile overlay, which is not under test here.
            environment.getPropertySources().addFirst(sources.get(0));
            return Binder.get(environment);
        } catch (IOException e) {
            throw new IllegalStateException("application.yml could not be read", e);
        }
    }

    private <T> T bind(String prefix, Class<T> type) {
        return binder.bind(prefix, type).orElseThrow(
                () -> new AssertionError("nothing bound at '" + prefix + "' - the block is missing or misnamed"));
    }

    @Test
    void modelTiersBindAndAreDistinct() {
        ModelTierProperties tiers = bind("app.ai.model", ModelTierProperties.class);

        assertThat(tiers.nameFor(ModelTier.FAST)).isEqualTo("gpt-4o-mini");
        assertThat(tiers.nameFor(ModelTier.CAPABLE)).isEqualTo("gpt-4o");
        assertThat(tiers.fast())
                .as("tiering only saves money if the two tiers are different models")
                .isNotEqualTo(tiers.capable());
    }

    /**
     * Map keys containing hyphens need bracket notation in YAML, or relaxed
     * binding mangles them - and a mangled key means the model is silently
     * unpriced and every cost figure is understated.
     */
    @Test
    void everyConfiguredTierHasAPriceUnderAKeyThatActuallyBinds() {
        ModelTierProperties tiers = bind("app.ai.model", ModelTierProperties.class);
        ModelPricingProperties pricing = bind("app.ai.pricing", ModelPricingProperties.class);

        assertThat(pricing.priceFor(tiers.fast()))
                .as("the fast tier's model must be priced or its spend reports as zero")
                .isPresent();
        assertThat(pricing.priceFor(tiers.capable())).isPresent();
        assertThat(pricing.priceFor(tiers.capable()).orElseThrow().inputPerMillionTokens())
                .isGreaterThan(pricing.priceFor(tiers.fast()).orElseThrow().inputPerMillionTokens());
    }

    @Test
    void ragKnobsBindIncludingEveryHybridRetrievalSetting() {
        RagProperties rag = bind("app.rag", RagProperties.class);

        assertThat(rag.chunkTokens()).isEqualTo(220);
        assertThat(rag.chunkOverlapTokens()).isEqualTo(40);
        assertThat(rag.topK()).isEqualTo(5);
        assertThat(rag.hybridEnabled()).isTrue();
        assertThat(rag.candidateMultiplier()).isEqualTo(4);
        assertThat(rag.fusionK()).isEqualTo(60);
        assertThat(rag.denseWeight()).isEqualTo(1.0);
        assertThat(rag.lexicalWeight()).isEqualTo(1.0);
        assertThat(rag.maxChunksPerDocument()).isEqualTo(2);
        assertThat(rag.candidateLimit())
                .as("retrieve wide, rank narrow - the candidate pool must exceed topK")
                .isGreaterThan(rag.topK());
    }

    @Test
    void chunkOverlapIsSmallerThanTheChunkItself() {
        RagProperties rag = bind("app.rag", RagProperties.class);

        assertThat(rag.chunkOverlapTokens())
                .as("overlap at or above the chunk size cannot advance and would never terminate")
                .isLessThan(rag.chunkTokens());
    }

    @Test
    void agentBoundsBindAndTheDeadIterationCapIsGone() {
        AgentProperties agent = bind("app.agent", AgentProperties.class);

        assertThat(agent.timeoutSeconds()).isEqualTo(30.0);
        assertThat(agent.maxConcurrentSteps()).isEqualTo(8);
        assertThat(binder.bind("app.agent.max-iterations", Integer.class).isBound())
                .as("the unreachable iteration cap was removed; leaving it in config would imply it still bounds something")
                .isFalse();
    }

    /**
     * Both default to off. A budget nobody has calibrated rejects real support
     * work; a judge tier that is on by default puts a billed, non-deterministic
     * model call into the evaluation run CI triggers by habit.
     */
    @Test
    void theTwoOptInFeaturesAreOffByDefault() {
        assertThat(bind("app.ai.budget", TokenBudgetProperties.class).enabled()).isFalse();
        assertThat(bind("app.eval", EvaluationProperties.class).llmJudgeEnabled()).isFalse();
    }

    @Test
    void semanticCacheBindsWithAConservativeThreshold() {
        SemanticCacheProperties cache = bind("app.ai.cache.semantic", SemanticCacheProperties.class);

        assertThat(cache.enabled()).isTrue();
        assertThat(cache.similarityThreshold())
                .as("a loose threshold serves the answer to a DIFFERENT question, confidently")
                .isGreaterThanOrEqualTo(0.9);
        assertThat(cache.maxEntriesPerScope()).isPositive();
        assertThat(cache.ttl()).isNotNull();
    }

    @Test
    void evaluationCaseCeilingIsSetSoARunawayDatasetCannotRunawaySpend() {
        assertThat(bind("app.eval", EvaluationProperties.class).maxCases()).isPositive();
    }

    /**
     * The shutdown window has to outlast the longest request it is protecting,
     * or graceful shutdown guarantees killing exactly the most expensive ones.
     */
    @Test
    void theShutdownWindowOutlastsTheAgentDeadline() {
        double agentTimeoutSeconds = bind("app.agent", AgentProperties.class).timeoutSeconds();
        java.time.Duration shutdown = binder
                .bind("spring.lifecycle.timeout-per-shutdown-phase", java.time.Duration.class)
                .orElseThrow(() -> new AssertionError("graceful shutdown has no configured timeout"));

        assertThat(shutdown.toMillis()).isGreaterThan((long) (agentTimeoutSeconds * 1000));
    }
}
