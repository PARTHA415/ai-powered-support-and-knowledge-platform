package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.ai.llm.LlmCallUsage;
import com.example.aiplatform.ai.llm.ModelTier;
import com.example.aiplatform.config.TokenBudgetProperties;
import com.example.aiplatform.exception.TokenBudgetExceededException;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.security.RedisFixedWindowRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBudgetGuardTest {

    @Mock
    private RedisFixedWindowRateLimiter counter;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TokenBudgetGuard guard(TokenBudgetProperties properties) {
        return new TokenBudgetGuard(counter, properties, new AiPipelineMetrics(meterRegistry));
    }

    private static TokenBudgetProperties enabled(long tokensPerWindow) {
        return new TokenBudgetProperties(true, tokensPerWindow, 3600);
    }

    @Test
    void aCallerUnderBudgetIsAllowedThrough() {
        when(counter.currentCount(anyString())).thenReturn(500L);

        assertThatCode(() -> guard(enabled(1000)).assertWithinBudget()).doesNotThrowAnyException();
    }

    @Test
    void aCallerAtOrOverBudgetIsRejectedAndTheRejectionIsCounted() {
        when(counter.currentCount(anyString())).thenReturn(1000L);

        assertThatThrownBy(() -> guard(enabled(1000)).assertWithinBudget())
                .isInstanceOf(TokenBudgetExceededException.class)
                .hasMessageContaining("Token budget exhausted");
        assertThat(meterRegistry.get("ai.safety.bound.triggered")
                .tag("reason", "token_budget_exceeded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aCompletedCallChargesPromptAndCompletionTokensTogether() {
        guard(enabled(1000)).record(new LlmCallUsage("gpt-4o", ModelTier.CAPABLE, 120, 0, 30));

        verify(counter).addAndGet(anyString(), org.mockito.ArgumentMatchers.eq(150L), anyInt());
    }

    /**
     * Disabled by default and inert when disabled: no Redis round trip on
     * either path. A budget nobody has calibrated yet should cost nothing, not
     * just permit everything.
     */
    @Test
    void aDisabledBudgetTouchesRedisOnNeitherPath() {
        TokenBudgetGuard disabled = guard(new TokenBudgetProperties(false, 1000, 3600));

        assertThatCode(disabled::assertWithinBudget).doesNotThrowAnyException();
        disabled.record(new LlmCallUsage("gpt-4o", ModelTier.CAPABLE, 900_000, 0, 900_000));

        verifyNoInteractions(counter);
    }

    @Test
    void aCallThatReportedNoUsageIsNotCharged() {
        guard(enabled(1000)).record(LlmCallUsage.none(ModelTier.CAPABLE));

        verify(counter, never()).addAndGet(anyString(), anyLong(), anyInt());
    }

    /**
     * Fails open. An unreachable Redis makes requests unmetered, never
     * universally rejected - the limiter returning 0 for an unavailable store
     * is what carries that through.
     */
    @Test
    void anUnreachableCounterLeavesRequestsUnmeteredRatherThanBlocked() {
        when(counter.currentCount(anyString())).thenReturn(0L);

        assertThatCode(() -> guard(enabled(1)).assertWithinBudget()).doesNotThrowAnyException();
    }
}
