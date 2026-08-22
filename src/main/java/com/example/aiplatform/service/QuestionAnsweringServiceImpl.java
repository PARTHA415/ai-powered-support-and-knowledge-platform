package com.example.aiplatform.service;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.model.SemanticSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The "generation" half of RAG, built on top of Phase 5's
 * {@link SemanticSearchService} (the "retrieval" half - embed query, find
 * nearest chunks, nothing more). Orchestrates the full pipeline: retrieve the
 * top-K nearest chunks, drop anything below the configured similarity
 * threshold, build a numbered context block out of what's left, render it
 * into a grounded prompt (see prompts/rag-*.st), and ask the LLM to answer
 * using only that context.
 */
@Service
public class QuestionAnsweringServiceImpl implements QuestionAnsweringService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAnsweringServiceImpl.class);

    private final SemanticSearchService semanticSearchService;
    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final RagProperties ragProperties;
    private final PromptInjectionGuard promptInjectionGuard;
    private final String model;

    public QuestionAnsweringServiceImpl(SemanticSearchService semanticSearchService,
                                         PromptBuilder promptBuilder,
                                         LlmClientService llmClientService,
                                         RagProperties ragProperties,
                                         PromptInjectionGuard promptInjectionGuard,
                                         @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.semanticSearchService = semanticSearchService;
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.ragProperties = ragProperties;
        this.promptInjectionGuard = promptInjectionGuard;
        this.model = model;
    }

    @Override
    public AskResponse answer(String question) {
        promptInjectionGuard.assertSafe(question);
        List<SemanticSearchResult> retrieved = semanticSearchService.search(question, ragProperties.topK());

        List<SemanticSearchResult> relevant = retrieved.stream()
                .filter(result -> result.similarity() >= ragProperties.similarityThreshold())
                .toList();

        log.debug("Retrieved {} chunks, {} met the similarity threshold ({}): {}",
                retrieved.size(), relevant.size(), ragProperties.similarityThreshold(),
                retrieved.stream().map(r -> r.documentTitle() + "=" + r.similarity()).toList());

        String context = buildContext(relevant);
        Prompt prompt = promptBuilder.buildRagPrompt(question, context);
        String answer = llmClientService.generate(prompt);

        return new AskResponse(answer, model, relevant);
    }

    /**
     * Instance method (not static) because it now sanitizes each chunk
     * through {@link PromptInjectionGuard#sanitize(String)} before splicing
     * it into the prompt - the indirect-injection defense: a knowledge-base
     * document is untrusted content the application retrieved, not
     * something the live caller typed, so any injection pattern found here
     * gets redacted rather than the whole request being rejected.
     */
    private String buildContext(List<SemanticSearchResult> chunks) {
        if (chunks.isEmpty()) {
            return "No relevant documentation was found in the knowledge base.";
        }
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            SemanticSearchResult chunk = chunks.get(i);
            context.append('[').append(i + 1).append("] (").append(chunk.documentTitle()).append(") ")
                    .append(promptInjectionGuard.sanitize(chunk.content())).append(System.lineSeparator());
        }
        return context.toString();
    }
}
