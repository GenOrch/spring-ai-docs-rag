package com.genorch.rag.observability.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genorch.rag.config.RagProperties;

/**
 * In-memory, bounded, thread-safe store for the two audit layers: request audits
 * ({@link RequestAudit}) and pipeline operation logs ({@link OperationLog}).
 *
 * <p>Both are ring buffers — the newest entries evict the oldest once the capacity is
 * reached, so memory stays bounded regardless of load. This is the source for the
 * {@code rag_logs} MCP tool ("what happened recently").
 *
 * <p>Request audits are additionally appended to a JSON-lines file when
 * {@code app.rag.mcp.audit-file-enabled} is true, giving a durable, append-only audit trail
 * separate from the in-memory window. The file rotates by size (~10 MB) and keeps a few
 * numbered backups, so it never grows without bound.
 */
@Component
public class AuditStore {

    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);

    /** Rotate the audit file once it exceeds ~10 MB, keeping the three most recent backups. */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ROTATED_FILES = 3;

    private final int auditCapacity;
    private final int operationCapacity;
    private final boolean auditFileEnabled;
    private final Path auditFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object auditFileLock = new Object();

    private final Deque<RequestAudit> audits = new ArrayDeque<>();
    private final Deque<OperationLog> operations = new ArrayDeque<>();

    public AuditStore(RagProperties properties) {
        this.auditCapacity = Math.max(1, properties.mcp().auditBufferSize());
        this.operationCapacity = Math.max(1, properties.mcp().operationBufferSize());
        this.auditFileEnabled = properties.mcp().auditFileEnabled();
        this.auditFile = Path.of(properties.mcp().auditFile());
        if (auditFileEnabled) {
            log.info("request audit file enabled: {}", this.auditFile.toAbsolutePath());
        }
    }

    public void record(RequestAudit audit) {
        synchronized (this) {
            audits.addLast(audit);
            while (audits.size() > auditCapacity) {
                audits.removeFirst();
            }
        }
        if (auditFileEnabled) {
            appendAudit(audit);
        }
    }

    public synchronized void record(OperationLog log) {
        operations.addLast(log);
        while (operations.size() > operationCapacity) {
            operations.removeFirst();
        }
    }

    /** Returns up to {@code limit} request audits, newest first. */
    public synchronized List<RequestAudit> audits(int limit) {
        return newestFirst(audits, limit);
    }

    /** Returns up to {@code limit} operation logs, newest first. */
    public synchronized List<OperationLog> operations(int limit) {
        return newestFirst(operations, limit);
    }

    private void appendAudit(RequestAudit audit) {
        synchronized (auditFileLock) {
            try {
                Files.createDirectories(auditFile.getParent());
                rotateIfNeeded();
                // Serialize via a map so the Instant is written as an ISO-8601 string
                // without needing jackson-datatype-jsr310 on the classpath.
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("traceId", audit.traceId());
                map.put("entryType", audit.entryType());
                map.put("endpoint", audit.endpoint());
                map.put("method", audit.method());
                map.put("args", audit.args());
                map.put("outcome", audit.outcome());
                map.put("error", audit.error());
                map.put("durationMs", audit.durationMs());
                map.put("timestamp", audit.timestamp().toString());
                String line = objectMapper.writeValueAsString(map) + System.lineSeparator();
                Files.write(auditFile, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            catch (Exception e) {
                log.warn("failed to append request audit to {}: {}", auditFile, e.getMessage());
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (rotateIfNeeded(auditFile, MAX_FILE_BYTES, MAX_ROTATED_FILES)) {
            log.info("rotated audit file {} (size threshold reached)", auditFile);
        }
    }

    /**
     * Rotates {@code file} once it reaches {@code maxBytes}: {@code file} becomes {@code file.1},
     * the previous {@code .1} becomes {@code .2}, and so on, keeping at most {@code maxFiles}
     * numbered backups (the oldest is dropped). Returns {@code true} when a rotation happened.
     *
     * <p>Package-private static so the rotation logic is unit-testable with a tiny threshold.
     */
    static boolean rotateIfNeeded(Path file, long maxBytes, int maxFiles) throws IOException {
        if (!Files.exists(file) || Files.size(file) < maxBytes) {
            return false;
        }
        Path oldest = rotatedPath(file, maxFiles);
        Files.deleteIfExists(oldest);
        for (int i = maxFiles - 1; i >= 1; i--) {
            Path from = rotatedPath(file, i);
            Path to = rotatedPath(file, i + 1);
            if (Files.exists(from)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, rotatedPath(file, 1));
        return true;
    }

    private static Path rotatedPath(Path file, int index) {
        return Path.of(file.toString() + "." + index);
    }

    private static <T> List<T> newestFirst(Deque<T> deque, int limit) {
        List<T> all = new ArrayList<>(deque);
        Collections.reverse(all);
        int n = limit <= 0 ? all.size() : Math.min(limit, all.size());
        return all.subList(0, n);
    }
}
