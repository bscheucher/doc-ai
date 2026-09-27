package com.learning.docai.api;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Echoes a valid incoming {@code X-Request-Id}, otherwise generates one (SPEC §3).
 * The id is exposed as a request attribute, as a response header and in the MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String requestId = normalise(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String normalise(String incoming) {
        if (incoming != null && !incoming.isBlank()) {
            try {
                return UUID.fromString(incoming.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // Not a valid UUID - fall through and generate one.
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Request id for the current request, or a fresh one if the filter did not run.
     */
    public static String currentRequestId(WebRequest request) {
        Object attribute = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return attribute instanceof String id ? id : UUID.randomUUID().toString();
    }
}
