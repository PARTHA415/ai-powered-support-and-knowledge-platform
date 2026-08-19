package com.example.aiplatform.controller;

import com.example.aiplatform.config.SecurityConfig;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.service.QuestionAnsweringService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuestionAnsweringController.class)
@Import(SecurityConfig.class)
class QuestionAnsweringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuestionAnsweringService questionAnsweringService;

    @Test
    @WithMockUser(roles = "USER")
    void postQaReturnsAnswerWithSourcesFromService() throws Exception {
        when(questionAnsweringService.answer(eq("How do I reset my password?")))
                .thenReturn(new AskResponse(
                        "Go to Settings > Security > Reset Password. [1]",
                        "gpt-4o-mini",
                        List.of(new SemanticSearchResult(
                                "Password Reset Guide", "Go to Settings > Security > Reset Password.", 0.92))));

        mockMvc.perform(post("/api/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How do I reset my password?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Go to Settings > Security > Reset Password. [1]"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.sources[0].documentTitle").value("Password Reset Guide"))
                .andExpect(jsonPath("$.sources[0].similarity").value(0.92));
    }

    @Test
    void postQaWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How do I reset my password?\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postQaWithBlankQuestionReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
