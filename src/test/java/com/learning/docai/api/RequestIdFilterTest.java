package com.learning.docai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void echoesAValidIncomingRequestId() throws Exception {
        String incoming = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(incoming);
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(incoming);
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertThatCode(() -> UUID.fromString(generated)).doesNotThrowAnyException();
    }

    @Test
    void replacesAnIncomingIdThatIsNotAUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "nicht-eine-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertThat(generated).isNotEqualTo("nicht-eine-uuid");
        assertThatCode(() -> UUID.fromString(generated)).doesNotThrowAnyException();
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(org.slf4j.MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
