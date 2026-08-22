package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.exception.ToolExecutionLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionGuardTest {

    @Test
    void callsWithinTheLimitAreAllowed() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(3, 6000));

        assertThatCode(() -> {
            guard.recordInvocation("getOrder");
            guard.recordInvocation("getOrder");
            guard.recordInvocation("getPaymentStatus");
        }).doesNotThrowAnyException();
    }

    @Test
    void theCallThatExceedsTheLimitIsRefused() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(2, 6000));

        guard.recordInvocation("getOrder");
        guard.recordInvocation("getOrder");

        assertThatThrownBy(() -> guard.recordInvocation("getOrder"))
                .isInstanceOf(ToolExecutionLimitExceededException.class);
    }

    @Test
    void resetAllowsANewBudgetOfCallsAfterwards() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(1, 6000));

        guard.recordInvocation("checkInventory");
        assertThatThrownBy(() -> guard.recordInvocation("checkInventory"))
                .isInstanceOf(ToolExecutionLimitExceededException.class);

        guard.reset();

        assertThatCode(() -> guard.recordInvocation("checkInventory")).doesNotThrowAnyException();
    }

    @Test
    void clearRemovesStateWithoutError() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(5, 6000));

        guard.recordInvocation("checkInventory");

        assertThatCode(guard::clear).doesNotThrowAnyException();
    }
}
