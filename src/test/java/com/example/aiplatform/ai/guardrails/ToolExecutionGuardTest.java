package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.exception.ToolExecutionLimitExceededException;
import com.example.aiplatform.observability.RequestContext;
import com.example.aiplatform.observability.RequestContextHolder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionGuardTest {

    @Test
    void callsWithinTheLimitAreAllowed() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(3, 6000));

        try (RequestContextHolder.Scope ignored = openContext()) {
            assertThatCode(() -> {
                guard.recordInvocation("getOrder");
                guard.recordInvocation("getOrder");
                guard.recordInvocation("getPaymentStatus");
            }).doesNotThrowAnyException();
        }
    }

    @Test
    void theCallThatExceedsTheLimitIsRefused() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(2, 6000));

        try (RequestContextHolder.Scope ignored = openContext()) {
            guard.recordInvocation("getOrder");
            guard.recordInvocation("getOrder");

            assertThatThrownBy(() -> guard.recordInvocation("getOrder"))
                    .isInstanceOf(ToolExecutionLimitExceededException.class);
        }
    }

    @Test
    void eachRequestGetsItsOwnBudget() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(1, 6000));

        try (RequestContextHolder.Scope ignored = openContext()) {
            guard.recordInvocation("checkInventory");
            assertThatThrownBy(() -> guard.recordInvocation("checkInventory"))
                    .isInstanceOf(ToolExecutionLimitExceededException.class);
        }

        try (RequestContextHolder.Scope ignored = openContext()) {
            assertThatCode(() -> guard.recordInvocation("checkInventory")).doesNotThrowAnyException();
        }
    }

    /**
     * The regression test for the reason the counter left this class.
     *
     * <p>While it lived in a ThreadLocal, two threads working on ONE request
     * each started counting from zero - so a limit of 2 permitted 2 per branch
     * and nothing anywhere said so. Propagating the context is what makes the
     * budget belong to the request rather than to whichever thread happened to
     * run part of it.
     */
    @Test
    void theBudgetIsSharedAcrossThreadsWorkingOnTheSameRequest() throws Exception {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(3, 6000));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (RequestContextHolder.Scope ignored = openContext()) {
            Callable<Void> twoCalls = () -> {
                guard.recordInvocation("getOrder");
                guard.recordInvocation("getOrder");
                return null;
            };

            List<Future<Void>> futures = List.of(
                    executor.submit(RequestContextHolder.propagate(twoCalls)),
                    executor.submit(RequestContextHolder.propagate(twoCalls)));

            int failures = 0;
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ToolExecutionLimitExceededException.class);
                    failures++;
                }
            }
            // Four calls against a budget of three: the fourth must be refused.
            assertThat(failures).isEqualTo(1);
            assertThat(RequestContextHolder.current().toolCallCount()).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A tool reached without a request context is a bug - some new entry point
     * that never opened one - and must say so rather than quietly starting a
     * fresh count, which is precisely the silent failure this design replaced.
     */
    @Test
    void aToolCallWithNoRequestContextFailsLoudly() {
        ToolExecutionGuard guard = new ToolExecutionGuard(new GuardrailProperties(5, 6000));

        assertThatThrownBy(() -> guard.recordInvocation("checkInventory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RequestContext");
    }

    private static RequestContextHolder.Scope openContext() {
        return RequestContextHolder.open(RequestContext.forRequest("test-correlation-id"));
    }
}
