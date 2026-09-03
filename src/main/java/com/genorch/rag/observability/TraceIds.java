package com.genorch.rag.observability;

/**
 * Best-effort access to the OpenTelemetry trace/span ids of the current request.
 *
 * <p>Spring Boot 4's OTel starter keeps the active span on the thread-local context, so
 * {@code Span.current()} returns the request's span inside a request. Both methods degrade
 * to {@code ""} when there is no active span (e.g. during startup), so callers can always
 * log a correlation id without branching.
 */
public final class TraceIds {

    private TraceIds() {
    }

    public static String traceId() {
        try {
            var context = io.opentelemetry.api.trace.Span.current().getSpanContext();
            return context.isValid() ? context.getTraceId() : "";
        }
        catch (Throwable ignored) {
            return "";
        }
    }

    public static String spanId() {
        try {
            var context = io.opentelemetry.api.trace.Span.current().getSpanContext();
            return context.isValid() ? context.getSpanId() : "";
        }
        catch (Throwable ignored) {
            return "";
        }
    }
}
