package com.genorch.rag.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the {@code /admin/*} and {@code /mcp} endpoints.
 *
 * <p>{@code /admin/ingest} re-crawls the corpus and re-embeds every chunk, so an
 * unauthenticated endpoint would let anyone burn API credits and (with randomly generated
 * chunk ids) inflate the vector store on every call. The MCP endpoint is guarded for the same
 * reason: its {@code rag_ask}/{@code rag_eval} tools trigger real LLM calls, and
 * {@code rag_logs} exposes request arguments. Loopback callers are always allowed so local
 * development stays convenient; remote callers must send {@code X-Admin-Token}.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    static final String TOKEN_HEADER = "X-Admin-Token";

    private final String expectedToken;

    public AdminAuthFilter(@Value("${app.rag.admin-token:}") String adminToken) {
        this.expectedToken = adminToken == null ? "" : adminToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isProtectedPath(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (isLoopback(request) || tokenMatches(request)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("admin request rejected: path={} remote={} tokenPresent={}",
                request.getRequestURI(), request.getRemoteAddr(), request.getHeader(TOKEN_HEADER) != null);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "admin endpoints require a valid " + TOKEN_HEADER + " header");
    }

    private boolean isProtectedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.startsWith("/admin") || path.startsWith("/mcp"));
    }

    private boolean isLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return "127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address);
    }

    /** Constant-time comparison so the token cannot be probed byte by byte. */
    private boolean tokenMatches(HttpServletRequest request) {
        String provided = request.getHeader(TOKEN_HEADER);
        if (expectedToken.isEmpty() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }
}
