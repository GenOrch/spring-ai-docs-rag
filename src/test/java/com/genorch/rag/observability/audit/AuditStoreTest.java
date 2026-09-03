package com.genorch.rag.observability.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.genorch.rag.config.RagProperties;

/** Verifies the bounded, newest-first behaviour of the in-memory audit ring buffer. */
class AuditStoreTest {

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
        assertEquals(2, audits.size());
        assertEquals("/c", audits.get(0).endpoint()); // newest first
        assertEquals("/b", audits.get(1).endpoint()); // /a evicted
    }

    @Test
    void limitClampsResultSize() {
        AuditStore store = new AuditStore(properties(10, 10, false));
        for (int i = 0; i < 5; i++) {
            store.record(new OperationLog("t", "C.m", "", "ok", "OK", "", i, Instant.now()));
        }
        assertEquals(2, store.operations(2).size());
        assertEquals(5, store.operations(100).size());
    }
}
