package com.genorch.rag.observability;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Populates the SLF4J MDC with the current trace/span id, so every diagnostic log line
 * carries the correlation id (Boot's default logback pattern already prints
 * {@code %X{traceId}}/{@code %X{spanId}}). Cleared in {@code finally} so pooled threads do
 * not leak a previous request's ids.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceIds.traceId();
        MDC.put("traceId", traceId.isBlank() ? "-" : traceId);
        MDC.put("spanId", TraceIds.spanId());
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            // Remove only our own keys so other components' MDC entries survive on the thread.
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }
}
