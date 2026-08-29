package com.example.aiplatform.config;

import com.example.aiplatform.ai.llm.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Maps each {@link ModelTier} to a concrete provider model name.
 *
 * <p>This is the only place in the application that pairs an intent with a
 * model name, which is what makes the tiering decision reviewable: reading two
 * lines of configuration tells you what the platform spends on planning versus
 * on the answers people read. Changing the pairing - or collapsing both tiers
 * onto one model, which is exactly what setting them equal does - needs no code
 * change and no redeploy of a service.
 *
 * <p>Deliberately NOT provider-namespaced. The names happen to be OpenAI model
 * identifiers in the default configuration, but they are carried to the
 * provider through Spring AI's portable {@code ChatOptions.model(...)}, so
 * pointing this at a different OpenAI-compatible endpoint (or another provider
 * entirely) is a configuration change here, not a code change in the AI layer.
 *
 * <p>{@code spring.ai.openai.chat.options.model} remains as the provider-level
 * default for any call that does not carry a per-prompt override. Nothing in
 * this application relies on that anymore - every prompt now names its tier -
 * but leaving it set means a future call site that forgets still gets a working
 * model rather than a provider error.
 */
@ConfigurationProperties(prefix = "app.ai.model")
public record ModelTierProperties(

        /** Machine-read output: agent planning, routing, structured extraction. */
        @DefaultValue("gpt-4o-mini") String fast,

        /** Human-read output: RAG answers, agent synthesis, tool explanations, chat. */
        @DefaultValue("gpt-4o") String capable
) {

    public String nameFor(ModelTier tier) {
        return tier == ModelTier.FAST ? fast : capable;
    }
}
