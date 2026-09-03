package com.genorch.rag.observability.audit;

import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * Records one {@link OperationLog} per pipeline method invocation (service / ingest /
 * retrieve / rerank), tagged with the current trace id so the fine-grained steps of a
 * single request can be grouped together. Complements the coarser {@link RequestAuditAspect}.
 */
@Aspect
@Component
public class OperationLogAspect {

    private final AuditStore store;

    public OperationLogAspect(AuditStore store) {
        this.store = store;
    }

    @Around("execution(* com.genorch.rag.service..*.*(..))"
            + " || execution(* com.genorch.rag.ingest..*.*(..))"
            + " || execution(* com.genorch.rag.retrieve..*.*(..))"
            + " || execution(* com.genorch.rag.rerank..*.*(..))")
    public Object logOperation(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = pjp.proceed();
            store.record(toLog(pjp, result, null, AuditSupport.elapsedMs(start)));
            return result;
        }
        catch (Throwable t) {
            store.record(toLog(pjp, null, t, AuditSupport.elapsedMs(start)));
            throw t;
        }
    }

    private OperationLog toLog(ProceedingJoinPoint pjp, Object result, Throwable error, long durationMs) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        return new OperationLog(
                AuditSupport.currentTraceId(),
                pjp.getTarget().getClass().getSimpleName() + "." + signature.getMethod().getName(),
                AuditSupport.summarizeArgs(pjp.getArgs()),
                error == null ? AuditSupport.summarizeResult(result) : "",
                error == null ? "OK" : "ERROR",
                error == null ? "" : String.valueOf(error.getMessage()),
                durationMs,
                Instant.now());
    }
}
