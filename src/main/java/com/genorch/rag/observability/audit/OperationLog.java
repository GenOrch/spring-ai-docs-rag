package com.genorch.rag.observability.audit;

import java.time.Instant;

/**
 * One record per pipeline method invocation (service / ingest / retrieve / rerank).
 * Correlated to its parent request via {@link #traceId()}, so the fine-grained steps of a
 * single request can be grouped together.
 */
public record OperationLog(
        String traceId,
        String method,      // className.methodName
        String args,        // truncated argument summary
        String result,      // truncated result summary
        String outcome,     // "OK" or "ERROR"
        String error,       // exception message when failed
        long durationMs,
        Instant timestamp) {
}
