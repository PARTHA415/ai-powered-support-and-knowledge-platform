package com.example.aiplatform.ai.llm;

/**
 * Which class of model a particular call needs - the portable half of "model
 * tiering."
 *
 * <p>Every LLM call in this application previously went to one model, named
 * once in {@code spring.ai.openai.chat.options.model}. That is the most
 * expensive possible configuration, because the calls are not the same kind of
 * work. The agent planner emits two booleans. The structured-output converter
 * fills in a small schema. Neither benefits from a frontier model, and both run
 * on every request that reaches them - so they dominate call volume while
 * contributing nothing that a cheap model cannot do. Synthesis is the opposite:
 * it is the answer the user actually reads, and it is where capability shows.
 *
 * <p>Splitting them is the single largest cost reduction available here, and it
 * is expressed as an intent ({@code FAST}) rather than a model name, for the
 * same reason {@link com.example.aiplatform.config.TemperatureProperties} names
 * temperatures by intent: the call site is stating what kind of work it is
 * doing, and the mapping from that to a concrete provider model belongs in
 * configuration ({@link com.example.aiplatform.config.ModelTierProperties}),
 * not in a service.
 *
 * <p><b>The mistake to avoid</b> is tiering by "importance" as a vague feeling.
 * Tier by whether the output is <em>read by a machine</em> (a schema, a
 * boolean, a route) or <em>read by a person</em> (an explanation, an answer).
 * The first tolerates a cheap model; the second is what you are paying for.
 */
public enum ModelTier {

    /**
     * Planning, routing, classification, and schema-filling - machine-read
     * output where a cheaper model is not a worse answer, only a cheaper one.
     */
    FAST,

    /**
     * Synthesis and anything a human reads as the answer: RAG generation, agent
     * final synthesis, tool-result explanation, open-ended chat.
     */
    CAPABLE
}
