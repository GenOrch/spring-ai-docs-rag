package com.genorch.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level RAG configuration, bound from the {@code app.rag.*} prefix.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        boolean ingestOnStartup,
        String dataDir,
        int embedBatchSize,
        /** Vector table name. */
        String tableName,
        /** System prompt used for generation; externalized so it can be tuned without a rebuild. */
        String systemPrompt,
        /** Token required for remote /admin calls; loopback calls are always allowed. */
        String adminToken,
        /** SSE stream timeout for a single /ask request. */
        long askTimeoutMs,
        Chunk chunk,
        Retrieve retrieve,
        Rerank rerank,
        Mcp mcp) {

    public record Chunk(int defaultChunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks) {
    }

    public record Retrieve(int vectorTopK, int keywordTopK, int fusionTopN, int rrfK) {
    }

    public record Rerank(boolean enabled, String endpoint, String model, int topN) {
    }

    /** Audit / operation-log ring-buffer sizing and the durable audit file, for {@code rag_logs}. */
    public record Mcp(int auditBufferSize, int operationBufferSize, boolean auditFileEnabled, String auditFile) {
    }
}
