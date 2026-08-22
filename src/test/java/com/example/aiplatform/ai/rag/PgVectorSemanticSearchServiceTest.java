package com.example.aiplatform.ai.rag;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.model.SimilarChunk;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.repository.DocumentChunkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgVectorSemanticSearchServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Test
    void searchEmbedsQueryAndConvertsDistanceToSimilarity() {
        float[] queryVector = {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("How do I reset my password?")).thenReturn(queryVector);
        when(documentChunkRepository.findNearest(queryVector, 5)).thenReturn(List.of(
                new SimilarChunk(1L, 10L, "Password Reset Guide", "Go to Settings > Security > Reset Password.", 0.1)));

        AiPipelineMetrics aiPipelineMetrics = new AiPipelineMetrics(new SimpleMeterRegistry());
        PgVectorSemanticSearchService service =
                new PgVectorSemanticSearchService(embeddingService, documentChunkRepository, aiPipelineMetrics);

        List<SemanticSearchResult> results = service.search("How do I reset my password?", 5);

        assertThat(results).hasSize(1);
        SemanticSearchResult result = results.get(0);
        assertThat(result.documentTitle()).isEqualTo("Password Reset Guide");
        assertThat(result.content()).isEqualTo("Go to Settings > Security > Reset Password.");
        assertThat(result.similarity()).isEqualTo(0.9);
    }

    @Test
    void searchRecordsVectorSearchLatencyAndRetrievedDocumentCountMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiPipelineMetrics aiPipelineMetrics = new AiPipelineMetrics(meterRegistry);
        float[] queryVector = {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("kafka consumer failures")).thenReturn(queryVector);
        when(documentChunkRepository.findNearest(queryVector, 5)).thenReturn(List.of(
                new SimilarChunk(1L, 10L, "Kafka Guide", "Restart the consumer group.", 0.2),
                new SimilarChunk(2L, 11L, "Kafka Guide", "Check offset commits.", 0.3)));

        PgVectorSemanticSearchService service =
                new PgVectorSemanticSearchService(embeddingService, documentChunkRepository, aiPipelineMetrics);

        service.search("kafka consumer failures", 5);

        assertThat(meterRegistry.get("rag.vector.search").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("rag.retrieved.documents").summary().totalAmount()).isEqualTo(2.0);
    }
}
