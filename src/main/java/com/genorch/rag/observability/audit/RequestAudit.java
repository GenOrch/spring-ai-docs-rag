package com.genorch.rag.observability.audit;

import java.time.Instant;

/**
 * One audit record per incoming request — an HTTP endpoint ({@code @RestController}) or an
 * MCP tool call ({@code @Tool}). This is the coarse-grained "who / when / what / result"
 * unit; the finer-grained per-method detail lives in {@link OperationLog}.
 */
public record RequestAudit(
        String traceId,
        String entryType,   // "HTTP" or "MCP"
        String endpoint,    // e.g. /ask, /admin/ingest, or the tool name
        String method,      // className.methodName
        String args,        // truncated argument summary
        String outcome,     // "OK" or "ERROR"
        String error,       // exception message when failed
        long durationMs,
        Instant timestamp) {
}
