package com.example.aiplatform.ai.tools;

import com.example.aiplatform.ai.eval.AiEvaluationReport;
import com.example.aiplatform.ai.eval.EvaluationCaseResult;
import com.example.aiplatform.ai.eval.ToolSelectionCase;
import com.example.aiplatform.ai.eval.ToolSelectionScorer;
import com.example.aiplatform.ai.guardrails.PatternBasedPromptInjectionGuard;
import com.example.aiplatform.ai.guardrails.ToolExecutionGuard;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.model.Role;
import com.example.aiplatform.security.TestPrincipals;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Lives in {@code ai.tools} (not {@code ai.eval}, where the other Phase 15
 * tests live) specifically so it can construct a real, package-private
 * {@link BusinessDataStore} - the same fixture data {@link SupportToolsTest}
 * uses, now driving a fixed evaluation dataset instead of hand-written
 * assertions. {@link ToolSelectionScorer} evaluates "given this tool
 * selection decision, does the real, production {@link SupportTools}
 * handle it correctly" (tool argument validation, ownership authorization,
 * not-found handling) - see its Javadoc for why evaluating whether a live
 * model would have MADE that selection is deliberately out of scope for
 * this repeatable, dataset-driven mechanism.
 *
 * Docker-free: {@link BusinessDataStore} is in-memory, and every tool call
 * here is a direct Java method call - no network, no LLM, no database.
 */
class ToolSelectionEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(ToolSelectionEvaluationTest.class);

    private final SupportTools supportTools = new SupportTools(
            new BusinessDataStore(),
            new ToolExecutionGuard(new GuardrailProperties(20, 6000)),
            mock(SemanticSearchService.class),
            new com.example.aiplatform.config.RagProperties(800, 100, 5, 0.5),
            new PatternBasedPromptInjectionGuard());

    @AfterEach
    void clearSecurityContext() {
        TestPrincipals.clear();
    }

    private List<ToolSelectionCase> loadDataset() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(
                new ClassPathResource("eval/tool-selection-dataset.json").getInputStream(),
                new TypeReference<List<ToolSelectionCase>>() {
                });
    }

    @Test
    void evaluatesToolSelectionAndArgumentCorrectnessAgainstTheRealFixtureData() throws IOException {
        List<ToolSelectionCase> dataset = loadDataset();

        List<EvaluationCaseResult> results = dataset.stream()
                .map(testCase -> {
                    if (testCase.callerCustomerId() != null) {
                        TestPrincipals.authenticateAs(TestPrincipals.customer(testCase.callerCustomerId()));
                    } else {
                        TestPrincipals.clear();
                    }
                    return ToolSelectionScorer.evaluate(supportTools, testCase);
                })
                .toList();

        AiEvaluationReport report = AiEvaluationReport.of(results);
        log.info("Tool selection / argument correctness evaluation:\n{}", report.toTable());

        assertThat(report.overallPassRate())
                .as("every case in the committed dataset is expected to pass - a failure here means either "
                        + "SupportTools' behavior regressed or the dataset itself needs updating")
                .isEqualTo(1.0);

        assertThat(findResult(results, "get-order-for-own-customer-succeeds").passed()).isTrue();
        assertThat(findResult(results, "get-order-for-another-customer-is-denied").category())
                .isEqualTo(com.example.aiplatform.ai.eval.EvaluationCategory.TOOL_SELECTION);
        assertThat(findResult(results, "malformed-order-id-is-rejected-as-invalid-argument").category())
                .isEqualTo(com.example.aiplatform.ai.eval.EvaluationCategory.TOOL_ARGUMENT_CORRECTNESS);
    }

    @Test
    void staffOverrideAppliesIdenticallyThroughTheEvaluationHarness() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.SUPPORT_AGENT));
        ToolSelectionCase testCase = new ToolSelectionCase("staff-override-check", "getOrder",
                java.util.Map.of("orderId", "ORD-1001"), null, ToolSelectionCase.ExpectedOutcome.SUCCESS, "SHIPPED");

        EvaluationCaseResult result = ToolSelectionScorer.evaluate(supportTools, testCase);

        assertThat(result.passed())
                .as("SUPPORT_AGENT can access any customer's order (Phase 11 RBAC) - the evaluation harness "
                        + "must observe the same real authorization rule, not a separate copy of it")
                .isTrue();
    }

    private static EvaluationCaseResult findResult(List<EvaluationCaseResult> results, String caseId) {
        return results.stream()
                .filter(r -> r.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No result for case " + caseId));
    }
}
