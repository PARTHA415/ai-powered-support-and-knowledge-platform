package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.guardrails.PatternBasedPromptInjectionGuard;
import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.rag.TokenAwareChunker;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.config.TestRagProperties;
import com.example.aiplatform.model.IngestDocumentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
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

/**
 * Ingestion orchestration only. How text is cut into chunks is
 * {@link TokenAwareChunker}'s job and is tested in
 * {@link com.example.aiplatform.ai.rag.TokenAwareChunkerTest} - splitting them
 * apart is the point of the extraction, and asserting on chunk boundaries from
 * here would re-couple the two.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    private static final RagProperties DEFAULT_RAG_PROPERTIES = TestRagProperties.defaults();

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

        String longContent = "word ".repeat(1000);
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
     * 100 KB document. With a small batch size and many chunks this must be a
     * handful of calls, not one per chunk, and {@code embed(String)} - the
     * single-text, cached path meant for queries - must not be used at all.
     */
    @Test
    void embedsInBatchesRatherThanOneCallPerChunk() {
        stubPersistence();
        stubEmbeddings();

        DocumentIngestionServiceImpl service = newService(TestRagProperties.chunking(20, 0, 10));

        IngestDocumentResponse response = service.ingest("Batched", null, uniqueWordContent(400), Map.of());

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

    private DocumentIngestionServiceImpl newService(RagProperties ragProperties) {
        return new DocumentIngestionServiceImpl(
                documentPersistence, embeddingService, ragProperties,
                new TokenAwareChunker(ragProperties), promptInjectionGuard);
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
}
