package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.model.IngestDocumentResponse;
import com.example.aiplatform.repository.DocumentChunkRepository;
import com.example.aiplatform.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceImplTest {

    private static final RagProperties DEFAULT_RAG_PROPERTIES = new RagProperties(800, 100, 5, 0.5);

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Test
    void splitsLongContentIntoMultipleChunksAndEmbedsEach() {
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunkRepository.save(any(DocumentChunk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f});

        DocumentIngestionServiceImpl service = new DocumentIngestionServiceImpl(
                documentRepository, documentChunkRepository, embeddingService, DEFAULT_RAG_PROPERTIES);

        String longContent = "word ".repeat(400); // ~2000 chars, well past the 800-char chunk size
        IngestDocumentResponse response = service.ingest("Kafka Troubleshooting", "kb/kafka.md", longContent);

        assertThat(response.title()).isEqualTo("Kafka Troubleshooting");
        assertThat(response.chunkCount()).isGreaterThan(1);
        verify(embeddingService, times(response.chunkCount())).embed(anyString());
        verify(documentChunkRepository, times(response.chunkCount())).saveEmbedding(any(), any());
    }

    @Test
    void shortContentProducesExactlyOneChunk() {
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunkRepository.save(any(DocumentChunk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f});

        DocumentIngestionServiceImpl service = new DocumentIngestionServiceImpl(
                documentRepository, documentChunkRepository, embeddingService, DEFAULT_RAG_PROPERTIES);

        IngestDocumentResponse response = service.ingest("Short Doc", null, "How do I reset my password?");

        assertThat(response.chunkCount()).isEqualTo(1);
    }

    @Test
    void configuredOverlapCausesConsecutiveChunksToShareWords() {
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunkRepository.save(any(DocumentChunk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f});

        RagProperties withOverlap = new RagProperties(50, 20, 5, 0.5);
        DocumentIngestionServiceImpl service = new DocumentIngestionServiceImpl(
                documentRepository, documentChunkRepository, embeddingService, withOverlap);

        List<String> chunks = ingestAndCaptureChunkTexts(service, uniqueWordContent(80));

        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(sharedWords(chunks.get(0), chunks.get(1))).isNotEmpty();
    }

    @Test
    void zeroOverlapProducesNoSharedWordsBetweenConsecutiveChunks() {
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunkRepository.save(any(DocumentChunk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f});

        RagProperties noOverlap = new RagProperties(50, 0, 5, 0.5);
        DocumentIngestionServiceImpl service = new DocumentIngestionServiceImpl(
                documentRepository, documentChunkRepository, embeddingService, noOverlap);

        List<String> chunks = ingestAndCaptureChunkTexts(service, uniqueWordContent(80));

        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(sharedWords(chunks.get(0), chunks.get(1))).isEmpty();
    }

    private List<String> ingestAndCaptureChunkTexts(DocumentIngestionServiceImpl service, String content) {
        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        service.ingest("Overlap Test", null, content);
        verify(documentChunkRepository, atLeast(2)).save(captor.capture());
        return captor.getAllValues().stream().map(DocumentChunk::getContent).toList();
    }

    private static String uniqueWordContent(int wordCount) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            content.append("token").append(i).append(' ');
        }
        return content.toString();
    }

    private static Set<String> sharedWords(String first, String second) {
        Set<String> firstWords = Set.of(first.split("\\s+"));
        Set<String> secondWords = Set.of(second.split("\\s+"));
        return firstWords.stream().filter(secondWords::contains).collect(Collectors.toSet());
    }
}
