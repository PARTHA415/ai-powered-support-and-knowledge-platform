package com.example.aiplatform.observability;

import jakarta.servlet.FilterChain;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAndExposesANewCorrelationIdWhenTheCallerSuppliedNone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        String[] mdcValueDuringChain = new String[1];
        doAnswer(invocation -> {
            mdcValueDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(mdcValueDuringChain[0]).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(mdcValueDuringChain[0]);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void reusesAnIncomingCorrelationIdInsteadOfMintingANewOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "upstream-trace-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo("upstream-trace-id-123");
    }

    @Test
    void clearsMdcEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, (req, resp) -> {
            throw new IOException("downstream failure");
        })).isInstanceOf(IOException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
