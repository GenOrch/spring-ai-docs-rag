package com.genorch.rag.observability.audit;

import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Audits every incoming request — any {@code @RestController} method (HTTP) and any
 * {@code @Tool}-annotated method (MCP). Produces one {@link RequestAudit} record per call,
 * which is the coarse-grained "who / when / what / result" trail.
 */
@Aspect
@Component
public class RequestAuditAspect {

    private final AuditStore store;

    public RequestAuditAspect(AuditStore store) {
        this.store = store;
    }

    @Around("(@within(org.springframework.web.bind.annotation.RestController) "
            + "|| @annotation(org.springframework.ai.mcp.annotation.McpTool))")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = pjp.proceed();
            store.record(toAudit(pjp, null, AuditSupport.elapsedMs(start)));
            return result;
        }
        catch (Throwable t) {
            store.record(toAudit(pjp, t, AuditSupport.elapsedMs(start)));
            throw t;
        }
    }

    private RequestAudit toAudit(ProceedingJoinPoint pjp, Throwable error, long durationMs) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        boolean mcp = signature.getMethod().isAnnotationPresent(McpTool.class);
        String endpoint = mcp ? signature.getMethod().getName() : httpPath(signature);
        return new RequestAudit(
                AuditSupport.currentTraceId(),
                mcp ? "MCP" : "HTTP",
                endpoint,
                pjp.getTarget().getClass().getSimpleName() + "." + signature.getMethod().getName(),
                AuditSupport.summarizeArgs(pjp.getArgs()),
                error == null ? "OK" : "ERROR",
                error == null ? "" : String.valueOf(error.getMessage()),
                durationMs,
                Instant.now());
    }

    /** Derives the concrete HTTP path from the mapping annotation, falling back to the method name. */
    private String httpPath(MethodSignature signature) {
        PostMapping post = signature.getMethod().getAnnotation(PostMapping.class);
        if (post != null && post.value().length > 0) {
            return String.join(",", post.value());
        }
        GetMapping get = signature.getMethod().getAnnotation(GetMapping.class);
        if (get != null && get.value().length > 0) {
            return String.join(",", get.value());
        }
        RequestMapping mapping = signature.getMethod().getAnnotation(RequestMapping.class);
        if (mapping != null && mapping.value().length > 0) {
            return String.join(",", mapping.value());
        }
        return signature.getMethod().getName();
    }
}
