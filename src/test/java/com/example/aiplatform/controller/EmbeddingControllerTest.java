package com.example.aiplatform.controller;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmbeddingController.class)
class EmbeddingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingService embeddingService;

    @Test
    void postEmbeddingsReturnsVectorAndMetadata() throws Exception {
        when(embeddingService.embed(eq("Kafka consumer lag")))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});

        mockMvc.perform(post("/api/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Kafka consumer lag\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.dimensions").value(3))
                .andExpect(jsonPath("$.vector.length()").value(3))
                .andExpect(jsonPath("$.vector[1]").value(0.2));
    }

    @Test
    void postEmbeddingsWithBlankTextReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
