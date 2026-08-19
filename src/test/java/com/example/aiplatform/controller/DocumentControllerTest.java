package com.example.aiplatform.controller;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.model.IngestDocumentResponse;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentIngestionService documentIngestionService;

    @MockBean
    private SemanticSearchService semanticSearchService;

    @Test
    void postDocumentsIngestsAndReturnsSummary() throws Exception {
        when(documentIngestionService.ingest(
                eq("Kafka Troubleshooting"), eq("kb/kafka.md"), eq("Restart the consumer group.")))
                .thenReturn(new IngestDocumentResponse(1L, "Kafka Troubleshooting", 1));

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kafka Troubleshooting\",\"source\":\"kb/kafka.md\","
                                + "\"content\":\"Restart the consumer group.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(1))
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    void postDocumentsWithBlankContentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kafka Troubleshooting\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postDocumentsSearchReturnsResultsFromService() throws Exception {
        when(semanticSearchService.search(eq("How do I reset my password?"), eq(5)))
                .thenReturn(List.of(new SemanticSearchResult(
                        "Password Reset Guide", "Go to Settings > Security > Reset Password.", 0.92)));

        mockMvc.perform(post("/api/documents/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"How do I reset my password?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentTitle").value("Password Reset Guide"))
                .andExpect(jsonPath("$[0].similarity").value(0.92));
    }

    @Test
    void postDocumentsSearchWithBlankQueryReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
