package com.genorch.rag.observability.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.genorch.rag.config.RagProperties;

/** Verifies the bounded in-memory ring buffer and the audit-file rotation. */
class AuditStoreTest {

    @TempDir
    Path dir;

    private static RagProperties properties(int auditBuf, int opBuf, boolean fileEnabled) {
        return new RagProperties(
                false, "./target/test-data", 10, "vector_store", "p", "", 120_000L,
                new RagProperties.Chunk(80, 20, 5, 100),
                new RagProperties.Retrieve(20, 20, 20, 60),
                new RagProperties.Rerank(true, "http://rerank", "m", 5),
                new RagProperties.Mcp(auditBuf, opBuf, fileEnabled, "target/test-audit.jsonl"));
    }

    @Test
    void ringBufferIsBoundedAndNewestFirst() {
        AuditStore store = new AuditStore(properties(2, 2, false));
        store.record(new RequestAudit("t1", "HTTP", "/a", "C.a", "", "OK", "", 1, Instant.now()));
        store.record(new RequestAudit("t2", "HTTP", "/b", "C.b", "", "OK", "", 2, Instant.now()));
        store.record(new RequestAudit("t3", "HTTP", "/c", "C.c", "", "OK", "", 3, Instant.now()));

        List<RequestAudit> audits = store.audits(10);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).endpoint()).isEqualTo("/c"); // newest first
        assertThat(audits.get(1).endpoint()).isEqualTo("/b"); // /a evicted
    }

    @Test
    void rotationRollsNumberedBackupsKeepingOrder() throws Exception {
        Path audit = dir.resolve("audit.jsonl");
        Files.writeString(audit, "x".repeat(20)); // main, 20 bytes >= threshold
        Files.writeString(dir.resolve("audit.jsonl.1"), "a");
        Files.writeString(dir.resolve("audit.jsonl.2"), "b");

        boolean rotated = AuditStore.rotateIfNeeded(audit, 10, 3);

        assertThat(rotated).isTrue();
        assertThat(Files.exists(audit)).isFalse();                              // moved away
        assertThat(Files.readString(dir.resolve("audit.jsonl.1"))).isEqualTo("x".repeat(20));
        assertThat(Files.readString(dir.resolve("audit.jsonl.2"))).isEqualTo("a");
        assertThat(Files.readString(dir.resolve("audit.jsonl.3"))).isEqualTo("b");
    }
}
