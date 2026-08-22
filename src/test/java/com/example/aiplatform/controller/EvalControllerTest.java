package com.example.aiplatform.controller;

import com.example.aiplatform.ai.eval.AiEvaluationReport;
import com.example.aiplatform.ai.eval.AiEvaluationService;
import com.example.aiplatform.ai.eval.EvaluationCaseResult;
import com.example.aiplatform.ai.eval.EvaluationCategory;
import com.example.aiplatform.ai.guardrails.ToolExecutionGuard;
import com.example.aiplatform.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Running the eval dataset makes real LLM calls (cost/latency) - an
 * internal quality-check endpoint, ADMIN-only, same tier as
 * {@code POST /api/embeddings}.
 */
@WebMvcTest(EvalController.class)
@Import({SecurityConfig.class, ToolExecutionGuard.class})
class EvalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiEvaluationService aiEvaluationService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void postRunReturnsTheEvaluationReport() throws Exception {
        EvaluationCaseResult result = new EvaluationCaseResult("case-1", EvaluationCategory.SAFETY_BEHAVIOR,
                "BLOCKED", "BLOCKED", 1.0, true, "Guardrail decision matched the expected outcome");
        when(aiEvaluationService.runFullEvaluation()).thenReturn(AiEvaluationReport.of(List.of(result)));

        mockMvc.perform(post("/api/eval/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].caseId").value("case-1"))
                .andExpect(jsonPath("$.results[0].passed").value(true))
                .andExpect(jsonPath("$.overallPassRate").value(1.0));
    }

    @Test
    @WithMockUser(roles = "USER")
    void postRunAsPlainUserIsForbidden() throws Exception {
        mockMvc.perform(post("/api/eval/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPPORT_AGENT")
    void postRunAsSupportAgentIsForbidden() throws Exception {
        mockMvc.perform(post("/api/eval/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postRunWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/eval/run"))
                .andExpect(status().isUnauthorized());
    }
}
