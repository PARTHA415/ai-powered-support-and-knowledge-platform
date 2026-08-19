package com.example.aiplatform.controller;

import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.ConfidenceLevel;
import com.example.aiplatform.model.SupportAnswer;
import com.example.aiplatform.model.SupportCategory;
import com.example.aiplatform.service.ChatService;
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

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @Test
    void postChatReturnsAnswerFromService() throws Exception {
        when(chatService.answer(eq("What is pgvector?")))
                .thenReturn(new ChatResponse("pgvector is a Postgres extension for vector similarity search.", "gpt-4o-mini"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What is pgvector?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("pgvector is a Postgres extension for vector similarity search."))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"));
    }

    @Test
    void postChatWithBlankMessageReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postStructuredChatReturnsParsedAnswerFromService() throws Exception {
        when(chatService.answerStructured(eq("How do I reset my password?")))
                .thenReturn(new SupportAnswer(
                        "Go to Settings > Security > Reset Password.",
                        SupportCategory.ACCOUNT,
                        ConfidenceLevel.HIGH,
                        false));

        mockMvc.perform(post("/api/chat/structured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How do I reset my password?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Go to Settings > Security > Reset Password."))
                .andExpect(jsonPath("$.category").value("ACCOUNT"))
                .andExpect(jsonPath("$.confidence").value("HIGH"))
                .andExpect(jsonPath("$.needsHumanEscalation").value(false));
    }

    @Test
    void postStructuredChatReturnsBadGatewayWhenModelResponseIsUnparseable() throws Exception {
        when(chatService.answerStructured(eq("What is pgvector?")))
                .thenThrow(new LlmIntegrationException(
                        "The LLM returned a response that did not match the expected format",
                        new RuntimeException("invalid JSON")));

        mockMvc.perform(post("/api/chat/structured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What is pgvector?\"}"))
                .andExpect(status().isBadGateway());
    }
}
