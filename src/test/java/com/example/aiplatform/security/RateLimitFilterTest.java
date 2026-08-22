package com.example.aiplatform.security;

import com.example.aiplatform.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestsWithinTheBudgetAllPassThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(3, 60));
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        }

        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void theRequestThatExceedsTheBudgetIsRejectedWith429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(2, 60));
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
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(1, 60));
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
        assertThat(bobResponse.getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(any(), any());
    }
}
