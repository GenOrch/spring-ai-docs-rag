package com.genorch.rag.document;

/**
 * Metadata keys carried by every {@link org.springframework.ai.document.Document} in the
 * RAG pipeline. Centralised here so the contract between the ingest side (which sets
 * {@link #SOURCE_URL}, {@link #TITLE}, {@link #CHUNK_INDEX}) and the
 * read side (which consumes them for citations and evaluation) stays in one place.
 */
public final class DocumentMeta {

    /** Canonical source page URL; also part of the position-stable chunk id. */
    public static final String SOURCE_URL = "source_url";

    /** Human-readable page title, used to decorate the numbered context and citations. */
    public static final String TITLE = "title";

    /** Zero-based chunk index within its source; part of the stable chunk id. */
    public static final String CHUNK_INDEX = "chunk_index";

    /**
     * SHA-256 of the chunk text. Used by incremental ingestion to detect when a chunk whose
     * (position-addressed) id already exists has actually changed content and must be re-embedded.
     */
    public static final String CONTENT_HASH = "content_hash";

    /** Spring AI version this document belongs to (e.g. "2.0.1"), for version-filtered retrieval. */
    public static final String VERSION = "version";

    private DocumentMeta() {
    }
}
