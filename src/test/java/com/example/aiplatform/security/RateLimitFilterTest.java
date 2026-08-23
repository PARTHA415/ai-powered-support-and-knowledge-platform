package com.example.aiplatform.security;

import com.example.aiplatform.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The limiter itself now lives in Redis (see {@link RedisFixedWindowRateLimiter}),
 * so these tests substitute an in-memory counter with the same contract. What
 * is under test here is the FILTER's behaviour - how it derives the caller key,
 * and what it does when the budget is gone - not Redis's ability to count.
 */
class RateLimitFilterTest {

    private final Map<String, Integer> counts = new HashMap<>();
    private final RedisFixedWindowRateLimiter limiter = mock(RedisFixedWindowRateLimiter.class);

    RateLimitFilterTest() {
        when(limiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    int limit = invocation.getArgument(1);
                    int count = counts.merge(key, 1, Integer::sum);
                    return count <= limit;
                });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestsWithinTheBudgetAllPassThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(limiter, new RateLimitProperties(3, 60, 10, 300));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        }

        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void theRequestThatExceedsTheBudgetIsRejectedWith429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(limiter, new RateLimitProperties(2, 60, 10, 300));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), thirdResponse, chain);

        assertThat(thirdResponse.getStatus()).isEqualTo(429);
        assertThat(thirdResponse.getContentAsString()).contains("Too Many Requests");
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void differentCallersHaveIndependentBudgets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(limiter, new RateLimitProperties(1, 60, 10, 300));
        FilterChain chain = mock(FilterChain.class);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of()));
        MockHttpServletResponse aliceResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), aliceResponse, chain);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", null, java.util.List.of()));
        MockHttpServletResponse bobResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), bobResponse, chain);

        assertThat(aliceResponse.getStatus()).isEqualTo(200);
        assertThat(bobResponse.getStatus())
                .as("bob's budget must not have been consumed by alice")
                .isEqualTo(200);
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void oneCallerExhaustingTheirBudgetDoesNotAffectAnother() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(limiter, new RateLimitProperties(1, 60, 10, 300));
        FilterChain chain = mock(FilterChain.class);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of()));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        MockHttpServletResponse aliceSecond = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), aliceSecond, chain);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", null, java.util.List.of()));
        MockHttpServletResponse bobFirst = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), bobFirst, chain);

        assertThat(aliceSecond.getStatus()).isEqualTo(429);
        assertThat(bobFirst.getStatus()).isEqualTo(200);
    }
}
