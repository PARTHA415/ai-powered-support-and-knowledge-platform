package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.rag.CitationValidator;
import com.example.aiplatform.ai.rag.SemanticAnswerCache;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.model.SemanticSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The "generation" half of RAG, built on top of {@link SemanticSearchService}
 * (the "retrieval" half - embed query, find and rank nearest chunks, nothing
 * more). Orchestrates the full pipeline: check whether this question has
 * effectively been answered already, otherwise retrieve, drop anything below
 * the relevance bar, build a numbered context block out of what's left, render
 * it into a grounded prompt (see prompts/rag-*.st), ask the LLM to answer using
 * only that context, and check the citations it produced before returning.
 *
 * <h2>The cache lookup comes first, and needs the embedding to do it</h2>
 *
 * Which is why this class now embeds the question itself rather than leaving
 * that entirely to the search service. It is not a second billed call:
 * {@link EmbeddingService#embed} is cached in Redis and keyed by model plus
 * text, so the search service's subsequent embed of the same question is a
 * cache hit. The alternative - threading the embedding through the search API
 * so it can be reused - would put a caching concern into a retrieval interface
 * to save a Redis round trip.
 *
 * <p>A cache hit skips retrieval AND generation, which is the whole point: it
 * removes the vector query, the ~6-15 second LLM call, and the tokens that call
 * would have billed. See {@link SemanticAnswerCache} for what it costs when the
 * similarity threshold is set too loosely, which is the risk that comes with it.
 */
@Service
public class QuestionAnsweringServiceImpl implements QuestionAnsweringService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAnsweringServiceImpl.class);

    private final SemanticSearchService semanticSearchService;
    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final RagProperties ragProperties;
    private final PromptInjectionGuard promptInjectionGuard;
    private final CitationValidator citationValidator;
    private final EmbeddingService embeddingService;
    private final SemanticAnswerCache semanticAnswerCache;

    public QuestionAnsweringServiceImpl(SemanticSearchService semanticSearchService,
                                         PromptBuilder promptBuilder,
                                         LlmClientService llmClientService,
                                         RagProperties ragProperties,
                                         PromptInjectionGuard promptInjectionGuard,
                                         CitationValidator citationValidator,
                                         EmbeddingService embeddingService,
                                         SemanticAnswerCache semanticAnswerCache) {
        this.semanticSearchService = semanticSearchService;
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.ragProperties = ragProperties;
        this.promptInjectionGuard = promptInjectionGuard;
        this.citationValidator = citationValidator;
        this.embeddingService = embeddingService;
        this.semanticAnswerCache = semanticAnswerCache;
    }

    @Override
    public AskResponse answer(String question) {
        // The injection guard runs BEFORE the cache lookup, not after. A
        // rejected question must be rejected whether or not something similar
        // was answered earlier - otherwise the cache becomes a way to get a
        // blocked question served.
        promptInjectionGuard.assertSafe(question);

        float[] questionEmbedding = embeddingService.embed(question);
        Optional<AskResponse> cached = semanticAnswerCache.lookup(question, questionEmbedding);
        if (cached.isPresent()) {
            log.debug("Answered '{}' from the semantic cache", question);
            return cached.get();
        }

        List<SemanticSearchResult> retrieved = semanticSearchService.search(question, ragProperties.topK());

        List<SemanticSearchResult> relevant = retrieved.stream()
                .filter(result -> result.isRelevantAt(ragProperties.similarityThreshold()))
                .toList();

        log.debug("Retrieved {} chunks, {} cleared the relevance bar (threshold {}): {}",
                retrieved.size(), relevant.size(), ragProperties.similarityThreshold(),
                retrieved.stream()
                        .map(r -> r.documentTitle() + "=" + r.similarity() + (r.lexicalMatch() ? "(lex)" : ""))
                        .toList());

        String context = buildContext(relevant);
        Prompt prompt = promptBuilder.buildRagPrompt(question, context);
        String answer = citationValidator.validate(llmClientService.generate(prompt), relevant.size(), "");

        AskResponse response = new AskResponse(answer, llmClientService.modelName(), relevant);
        // Cached only when the answer was actually grounded in something. An
        // "I don't have enough information" answer is correct today and wrong
        // the moment the missing document is ingested - caching it would keep
        // serving the gap for the whole TTL after it had been filled.
        if (!relevant.isEmpty()) {
            semanticAnswerCache.store(question, questionEmbedding, response);
        }
        return response;
    }

    /**
     * Instance method (not static) because it sanitizes each chunk through
     * {@link PromptInjectionGuard#sanitize(String)} before splicing it into the
     * prompt - the indirect-injection defense: a knowledge-base document is
     * untrusted content the application retrieved, not something the live caller
     * typed, so any injection pattern found here gets redacted rather than the
     * whole request being rejected.
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
