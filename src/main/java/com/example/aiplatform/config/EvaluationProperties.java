package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How much the evaluation harness is allowed to do, and to spend.
 *
 * @param llmJudgeEnabled whether the LLM-as-judge tier runs alongside the
 *                        deterministic scorers. OFF by default, deliberately:
 *                        it adds a billed model call per RAG case and makes the
 *                        harness non-deterministic, and neither of those belongs
 *                        in the run a developer or CI job triggers by habit. The
 *                        lexical scorers still run either way, so the default
 *                        configuration is a complete evaluation, not a crippled
 *                        one.
 * @param maxCases        a hard ceiling on how many cases one run may execute.
 *                        The eval endpoint makes real, billed calls in a loop
 *                        over a file - a dataset that grows without anyone
 *                        noticing is the shape of an accidental spend, and a
 *                        ceiling turns that into a truncated run and a log line
 *                        instead of a bill.
 */
@ConfigurationProperties(prefix = "app.eval")
public record EvaluationProperties(
        @DefaultValue("false") boolean llmJudgeEnabled,
        @DefaultValue("200") int maxCases
) {
}
