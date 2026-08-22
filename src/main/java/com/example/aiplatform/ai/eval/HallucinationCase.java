package com.example.aiplatform.ai.eval;

import java.util.List;

/**
 * A question with deliberately NO correct answer available to the
 * application (outside the knowledge base, no matching tool, no relevant
 * data) - the correct behavior is an honest "I don't have enough
 * information," and a hallucination is the model inventing a specific,
 * plausible-sounding answer anyway. forbiddenFabrications lists the
 * telltale specific details a fabricated answer to THIS question would
 * contain (a temperature, a tracking number, a made-up policy) - their
 * absence is what distinguishes "honestly declined" from "confidently
 * wrong."
 */
public record HallucinationCase(
        String id,
        String question,
        List<String> forbiddenFabrications
) {
}
