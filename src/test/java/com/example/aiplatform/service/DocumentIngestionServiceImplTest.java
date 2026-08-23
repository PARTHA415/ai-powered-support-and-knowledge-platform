package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.guardrails.PatternBasedPromptInjectionGuard;
import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.IngestDocumentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    private static final RagProperties DEFAULT_RAG_PROPERTIES = new RagProperties(800, 100, 32, 5, 0.5);

    private final PromptInjectionGuard promptInjectionGuard = new PatternBasedPromptInjectionGuard();

    @Mock
    private DocumentPersistence documentPersistence;

    @Mock
    private EmbeddingService embeddingService;

    @Test
    void splitsLongContentIntoMultipleChunksAndEmbedsThemAll() {
        stubPersistence();
        stubEmbeddings();

        DocumentIngestionServiceImpl service = newService(DEFAULT_RAG_PROPERTIES);

        String longContent = "word ".repeat(400); // ~2000 chars, well past the 800-char chunk size
        IngestDocumentResponse response = service.ingest("Kafka Troubleshooting", "kb/kafka.md", longContent, Map.of());

        assertThat(response.title()).isEqualTo("Kafka Troubleshooting");
        assertThat(response.chunkCount()).isGreaterThan(1);
        assertThat(capturedChunks()).hasSize(response.chunkCount());
    }

    @Test
    void shortContentProducesExactlyOneChunk() {
        stubPersistence();
        stubEmbeddings();

        IngestDocumentResponse response = newService(DEFAULT_RAG_PROPERTIES)
                .ingest("Short Doc", null, "How do I reset my password?", Map.of());

        assertThat(response.chunkCount()).isEqualTo(1);
    }

    /**
     * The regression test for the per-chunk embedding call. Ingestion used to
     * make one sequential, billed HTTP round trip per chunk - roughly 130 for a
     * 100 KB document. With a batch size of 10 and 25 chunks this must be three
     * calls, not twenty-five, and {@code embed(String)} - the single-text,
     * cached path meant for queries - must not be used at all.
     */
    @Test
    void embedsInBatchesRatherThanOneCallPerChunk() {
        stubPersistence();
        stubEmbeddings();

        // 50-char chunks over ~1250 chars of content gives ~25 chunks.
        RagProperties smallBatches = new RagProperties(50, 0, 10, 5, 0.5);
        DocumentIngestionServiceImpl service = newService(smallBatches);

        IngestDocumentResponse response = service.ingest("Batched", null, uniqueWordContent(200), Map.of());

        int expectedBatches = (int) Math.ceil(response.chunkCount() / 10.0);
        verify(embeddingService, times(expectedBatches)).embedAll(anyList());
        verify(embeddingService, never()).embed(anyString());
        assertThat(expectedBatches).isLessThan(response.chunkCount());
    }

    @Test
    void recordsTheEmbeddingModelAlongsideEveryStoredVector() {
        stubPersistence();
        stubEmbeddings();

        newService(DEFAULT_RAG_PROPERTIES).ingest("Model Tracked", null, "short content", Map.of());

        verify(documentPersistence).saveEmbeddings(anyList(), anyList(), eq("text-embedding-3-small"));
    }

    @Test
    void metadataIsPassedThroughToPersistence() {
        stubPersistence();
        stubEmbeddings();

        Map<String, String> metadata = Map.of("product", "kafka", "audience", "internal");
        newService(DEFAULT_RAG_PROPERTIES).ingest("Runbook", null, "content", metadata);

        verify(documentPersistence).saveDocumentAndChunks(eq("Runbook"), eq(null), eq(metadata), anyList());
    }

    @Test
    void configuredOverlapCausesConsecutiveChunksToShareWords() {
        stubPersistence();
        stubEmbeddings();

        newService(new RagProperties(50, 20, 32, 5, 0.5))
                .ingest("Overlap Test", null, uniqueWordContent(80), Map.of());

        List<String> chunks = capturedChunks();
        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(sharedWords(chunks.get(0), chunks.get(1))).isNotEmpty();
    }

    @Test
    void zeroOverlapProducesNoSharedWordsBetweenConsecutiveChunks() {
        stubPersistence();
        stubEmbeddings();

        newService(new RagProperties(50, 0, 32, 5, 0.5))
                .ingest("Overlap Test", null, uniqueWordContent(80), Map.of());

        List<String> chunks = capturedChunks();
        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(sharedWords(chunks.get(0), chunks.get(1))).isEmpty();
    }

    private DocumentIngestionServiceImpl newService(RagProperties ragProperties) {
        return new DocumentIngestionServiceImpl(
                documentPersistence, embeddingService, ragProperties, promptInjectionGuard);
    }

    private void stubPersistence() {
        when(documentPersistence.saveDocumentAndChunks(anyString(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    List<String> chunks = invocation.getArgument(3);
                    List<Long> ids = IntStream.range(0, chunks.size()).mapToObj(Long::valueOf).toList();
                    return new DocumentPersistence.StoredDocument(1L, invocation.getArgument(0), ids);
                });
    }

    private void stubEmbeddings() {
        when(embeddingService.modelName()).thenReturn("text-embedding-3-small");
        when(embeddingService.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return batch.stream().map(text -> new float[] {0.1f}).toList();
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedChunks() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentPersistence).saveDocumentAndChunks(anyString(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private static String uniqueWordContent(int wordCount) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            content.append("token").append(i).append(' ');
        }
        return content.toString().strip();
    }

    private static Set<String> sharedWords(String first, String second) {
        Set<String> firstWords = new HashSet<>(Arrays.asList(first.split("\\s+")));
        Set<String> secondWords = new HashSet<>(Arrays.asList(second.split("\\s+")));
        firstWords.retainAll(secondWords);
        return firstWords;
    }
}
