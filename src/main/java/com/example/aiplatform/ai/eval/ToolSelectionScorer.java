package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.exception.InvalidToolArgumentException;
import com.example.aiplatform.exception.ToolResourceNotFoundException;
import com.example.aiplatform.exception.UnauthorizedToolAccessException;

/**
 * Dispatches a {@link ToolSelectionCase} directly to the named
 * {@link SupportTools} method (a plain Java call, not the JSON/ToolCallback
 * path {@code SupportToolsTest} exercises - simpler, and just as real: the
 * method under test is identical either way) and compares the outcome class
 * against what was expected. Package-private and static, same shape as
 * {@link AnswerQualityScorer} - pure scoring logic, no Spring.
 *
 * Deliberately NOT wired into {@link AiEvaluationService}/the
 * {@code POST /api/eval/run} endpoint: exercising the {@code DENIED} cases
 * meaningfully requires evaluating as several different caller identities
 * in one run (Phase 11's ownership check depends entirely on who's
 * authenticated), and a production endpoint capable of impersonating
 * arbitrary customers to test authorization would itself be a security
 * anti-pattern. This runs from test code instead, using the same
 * {@code TestPrincipals} mechanism {@code SupportToolsTest} already uses to
 * simulate different callers safely, outside of any live request path.
 *
 * Public (unlike the package-private {@link AnswerQualityScorer}) purely so
 * its test - which needs the package-private {@code BusinessDataStore} to
 * build a real {@code SupportTools}, and therefore has to live in the
 * {@code ai.tools} package - can call it from across packages.
 */
public final class ToolSelectionScorer {

    private ToolSelectionScorer() {
    }

    public static EvaluationCaseResult evaluate(SupportTools supportTools, ToolSelectionCase testCase) {
        EvaluationCategory category = testCase.expectedOutcome() == ToolSelectionCase.ExpectedOutcome.INVALID_ARGUMENT
                ? EvaluationCategory.TOOL_ARGUMENT_CORRECTNESS
                : EvaluationCategory.TOOL_SELECTION;
        String expected = "tool=" + testCase.toolName() + " args=" + testCase.arguments()
                + " -> " + testCase.expectedOutcome()
                + (testCase.expectedResultContains() == null ? "" : " containing \"" + testCase.expectedResultContains() + "\"");

        try {
            Object result = dispatch(supportTools, testCase.toolName(), testCase.arguments());
            String actual = "SUCCESS: " + result;
            boolean outcomeMatches = testCase.expectedOutcome() == ToolSelectionCase.ExpectedOutcome.SUCCESS;
            boolean contentMatches = testCase.expectedResultContains() == null
                    || String.valueOf(result).contains(testCase.expectedResultContains());
            boolean passed = outcomeMatches && contentMatches;
            String reason = !outcomeMatches
                    ? "Expected " + testCase.expectedOutcome() + " but the call succeeded"
                    : contentMatches ? "Tool call succeeded with the expected result"
                    : "Tool call succeeded but result did not contain \"" + testCase.expectedResultContains() + "\"";
            return EvaluationCaseResult.instant(testCase.id(), category, expected, actual, passed ? 1.0 : 0.0,
                    passed, reason);
        } catch (UnauthorizedToolAccessException e) {
            return outcomeResult(testCase, category, expected, "DENIED: " + e.getMessage(),
                    ToolSelectionCase.ExpectedOutcome.DENIED, "Correctly denied by tool-level authorization",
                    "Expected " + testCase.expectedOutcome() + " but the call was denied");
        } catch (InvalidToolArgumentException e) {
            return outcomeResult(testCase, category, expected, "INVALID_ARGUMENT: " + e.getMessage(),
                    ToolSelectionCase.ExpectedOutcome.INVALID_ARGUMENT, "Malformed argument correctly rejected",
                    "Expected " + testCase.expectedOutcome() + " but the argument was rejected as invalid");
        } catch (ToolResourceNotFoundException e) {
            return outcomeResult(testCase, category, expected, "NOT_FOUND: " + e.getMessage(),
                    ToolSelectionCase.ExpectedOutcome.NOT_FOUND, "Well-formed but nonexistent ID correctly reported as not found",
                    "Expected " + testCase.expectedOutcome() + " but the resource was reported as not found");
        }
    }

    private static EvaluationCaseResult outcomeResult(ToolSelectionCase testCase, EvaluationCategory category,
                                                        String expected, String actual,
                                                        ToolSelectionCase.ExpectedOutcome matchingOutcome,
                                                        String passReason, String failReason) {
        boolean passed = testCase.expectedOutcome() == matchingOutcome;
        return EvaluationCaseResult.instant(testCase.id(), category, expected, actual, passed ? 1.0 : 0.0, passed,
                passed ? passReason : failReason);
    }

    private static Object dispatch(SupportTools supportTools, String toolName, java.util.Map<String, String> arguments) {
        return switch (toolName) {
            case "getOrder" -> supportTools.getOrder(arguments.get("orderId"));
            case "getPaymentStatus" -> supportTools.getPaymentStatus(arguments.get("orderId"));
            case "getShipmentStatus" -> supportTools.getShipmentStatus(arguments.get("orderId"));
            case "getCustomer" -> supportTools.getCustomer(arguments.get("customerId"));
            case "checkInventory" -> supportTools.checkInventory(arguments.get("productId"));
            default -> throw new IllegalArgumentException("Unknown tool in evaluation dataset: " + toolName);
        };
    }
}
