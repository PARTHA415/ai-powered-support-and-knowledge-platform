package com.example.aiplatform.controller;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.SecurityConfig;
import com.example.aiplatform.model.IngestDocumentResponse;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ingestion (writing to the knowledge base) is a staff action -
 * SUPPORT_AGENT/ADMIN only, per SecurityConfig - while search is open to any
 * authenticated role. These tests exercise both halves of that rule, not
 * just "authenticated or not."
 */
@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentIngestionService documentIngestionService;

    @MockBean
    private SemanticSearchService semanticSearchService;

    @Test
    @WithMockUser(roles = "SUPPORT_AGENT")
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
    @WithMockUser(roles = "ADMIN")
    void postDocumentsAsAdminIsAllowed() throws Exception {
        when(documentIngestionService.ingest(eq("Doc"), isNull(), eq("content")))
                .thenReturn(new IngestDocumentResponse(2L, "Doc", 1));

        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Doc\",\"content\":\"content\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postDocumentsAsPlainUserIsForbidden() throws Exception {
        // A customer can search the knowledge base but must not be able to
        // write to it - RBAC enforced at the API layer, before the request
        // ever reaches DocumentIngestionService.
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kafka Troubleshooting\",\"content\":\"Restart the consumer group.\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postDocumentsWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kafka Troubleshooting\",\"content\":\"Restart the consumer group.\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "SUPPORT_AGENT")
    void postDocumentsWithBlankContentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kafka Troubleshooting\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
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
    @WithMockUser(roles = "USER")
    void postDocumentsSearchWithBlankQueryReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/documents/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
