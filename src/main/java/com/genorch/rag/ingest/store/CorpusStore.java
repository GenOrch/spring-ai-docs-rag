package com.genorch.rag.ingest.store;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.document.Document;

/**
 * Storage view of the already-ingested corpus.
 *
 * <p>The RAG pipeline needs two corpus-level operations that Spring AI's {@code VectorStore}
 * interface does not expose: "does the corpus exist?" and "give me every chunk back" (the
 * latter rebuilds the in-memory BM25 index on startup and powers incremental ingestion).
 * The corpus itself is persisted inside the pgvector table by the vector store, so this
 * interface only reads it back — there is nothing to save or load separately.
 */
public interface CorpusStore {

    /**
     * True when a corpus has already been ingested and can be reused without re-embedding.
     *
     * @throws IOException when the store cannot be queried (e.g. the database is unreachable).
     *                     Callers must treat this as "unknown", not "empty".
     */
    boolean exists() throws IOException;

    /** Reads the full corpus back, used to rebuild the in-memory BM25 index after a restart. */
    List<Document> readAll() throws IOException;
}
