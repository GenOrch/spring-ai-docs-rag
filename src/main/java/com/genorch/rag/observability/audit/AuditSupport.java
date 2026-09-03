package com.genorch.rag.observability.audit;

import java.util.Collection;
import java.util.Map;

import org.reactivestreams.Publisher;

import com.genorch.rag.observability.TraceIds;

/**
 * Small shared helpers for the audit aspects: trace-id extraction, timing, and lossy
 * argument / result summarisation (values are truncated so the in-memory log stays small
 * and never leaks an unbounded object graph).
 */
final class AuditSupport {

    private AuditSupport() {
    }

    /** @see TraceIds#traceId() */
    static String currentTraceId() {
        return TraceIds.traceId();
    }

    static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(args[i]));
        }
        return truncate(sb.toString(), 200);
    }

    static String summarizeResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof Publisher<?>) {
            return "Publisher"; // Flux/Mono toString would hang or dump the pipeline
        }
        if (result instanceof Collection<?> c) {
            return c.getClass().getSimpleName() + "(" + c.size() + ")";
        }
        if (result instanceof Map<?, ?> m) {
            return "Map(" + m.size() + ")";
        }
        return truncate(String.valueOf(result), 200);
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
