package com.example.aiplatform.model;

import java.util.List;

/**
 * sources lists only the chunks that met the similarity threshold and were
 * actually included in the prompt's context - not every chunk retrieval
 * considered, so this doubles as the answer's citation list.
 */
public record AskResponse(
        String answer,
        String model,
        List<SemanticSearchResult> sources
) {
}
