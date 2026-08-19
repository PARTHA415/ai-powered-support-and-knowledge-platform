package com.example.aiplatform.controller;

import com.example.aiplatform.config.SecurityConfig;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.service.SupportAssistantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupportAssistantController.class)
@Import(SecurityConfig.class)
class SupportAssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SupportAssistantService supportAssistantService;

    @Test
    @WithMockUser(roles = "USER")
    void postAssistReturnsAnswerFromService() throws Exception {
        when(supportAssistantService.assist(eq("What's the status of order ORD-1001?")))
                .thenReturn(new ChatResponse("Order ORD-1001 is currently SHIPPED.", "gpt-4o-mini"));

        mockMvc.perform(post("/api/support/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What's the status of order ORD-1001?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Order ORD-1001 is currently SHIPPED."))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"));
    }

    @Test
    void postAssistWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/support/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postAssistWithBlankMessageReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/support/assist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
