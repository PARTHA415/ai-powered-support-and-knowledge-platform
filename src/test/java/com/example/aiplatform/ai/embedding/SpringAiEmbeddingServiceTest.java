package com.example.aiplatform.ai.embedding;

import com.example.aiplatform.exception.LlmIntegrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAiEmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Test
    void embedDelegatesToEmbeddingModelAndReturnsVector() {
        float[] expected = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed("How do I reset my password?")).thenReturn(expected);

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(embeddingModel);
        float[] actual = service.embed("How do I reset my password?");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void wrapsEmbeddingModelFailuresInLlmIntegrationException() {
        when(embeddingModel.embed("bad input")).thenThrow(new RuntimeException("provider error"));

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(embeddingModel);

        assertThatThrownBy(() -> service.embed("bad input"))
                .isInstanceOf(LlmIntegrationException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
