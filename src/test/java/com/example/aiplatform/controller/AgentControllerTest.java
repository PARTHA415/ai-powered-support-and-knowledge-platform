package com.example.aiplatform.controller;

import com.example.aiplatform.config.SecurityConfig;
import com.example.aiplatform.model.AgentAuditTrail;
import com.example.aiplatform.model.AgentResponse;
import com.example.aiplatform.model.AgentStepRecord;
import com.example.aiplatform.service.AgentService;
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

/**
 * {@link SecurityConfig} is imported explicitly so this slice test exercises
 * the application's real authorization rules, not Spring Boot's default
 * "require auth for everything, no custom roles" fallback that would apply
 * to an unconfigured security auto-configuration.
 */
@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

    @Test
    @WithMockUser(roles = "USER")
    void postAskReturnsAnswerAndAuditTrailFromService() throws Exception {
        AgentAuditTrail trail = new AgentAuditTrail("req-1", "What's the status of order ORD-1001?",
                false, true,
                List.of(new AgentStepRecord("PLANNING", true, "needsBusinessTool=true", 10),
                        new AgentStepRecord("BUSINESS_TOOL", true, "Tool-enabled LLM call completed", 200),
                        new AgentStepRecord("FINALIZE", true, "Final answer generated", 150)),
                false, false, 360);
        when(agentService.handle(eq("conv-1"), eq("What's the status of order ORD-1001?")))
                .thenReturn(new AgentResponse("Order ORD-1001 is SHIPPED.", "gpt-4o-mini", trail));

        mockMvc.perform(post("/api/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\","
                                + "\"message\":\"What's the status of order ORD-1001?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Order ORD-1001 is SHIPPED."))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.auditTrail.businessToolPlanned").value(true))
                .andExpect(jsonPath("$.auditTrail.knowledgeBasePlanned").value(false))
                .andExpect(jsonPath("$.auditTrail.steps.length()").value(3))
                .andExpect(jsonPath("$.auditTrail.maxIterationsExceeded").value(false));
    }

    @Test
    void postAskWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postAskWithBlankConversationIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"\",\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postAskWithBlankMessageReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
