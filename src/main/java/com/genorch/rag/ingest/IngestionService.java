package com.genorch.rag.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.genorch.rag.config.RagProperties;
import com.genorch.rag.document.DocumentMeta;
import com.genorch.rag.ingest.store.CorpusStore;
import com.genorch.rag.retrieve.LuceneKeywordIndex;

/**
 * Orchestrates the ingestion pipeline: read (all {@link DocumentReader}s) -> chunk -> embed
 * (vector store) + index (keyword). Both downstream stores receive the same chunks so hybrid
 * retrieval sees a consistent document set.
 *
 * <p>The document sources are injected as a {@code List<DocumentReader>}, so the pipeline is
 * source-agnostic: adding a new document type is a new reader bean, not a change here.
 *
 * <p>The already-ingested corpus is read back through {@link CorpusStore} (SQL) for
 * incremental ingestion and for rebuilding the keyword index on startup; persistence itself
 * is handled by the pgvector-backed {@link VectorStore}.
 *
 * <p>Chunk ids are <em>position-stable</em>: {@code sha256(version#sourceUrl + "#" + chunkIndex)}.
 * A stable id makes ingestion idempotent (upsert by id) — re-ingesting the same corpus updates
 * the existing rows instead of appending duplicates, which is what happens with randomly
 * generated ids. A per-chunk content hash ({@link DocumentMeta#CONTENT_HASH}) additionally
 * detects when a chunk's <em>text</em> changed, so a modified chunk is re-embedded even though
 * its id is unchanged.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<DocumentReader> readers;
    private final TokenTextSplitter splitter;
    private final VectorStore vectorStore;
    private final LuceneKeywordIndex keywordIndex;
    private final CorpusStore corpusStore;
    private final RagProperties properties;

    public IngestionService(List<DocumentReader> readers, TokenTextSplitter splitter, VectorStore vectorStore,
            LuceneKeywordIndex keywordIndex, CorpusStore corpusStore, RagProperties properties) {
        this.readers = readers;
        this.splitter = splitter;
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.corpusStore = corpusStore;
        this.properties = properties;
    }

    /**
     * Restore an already ingested corpus if present, otherwise do a fresh ingest.
     *
     * <p>{@code exists()} throws on a connection failure (database-backed store), so a
     * transient database outage fails fast instead of being mistaken for an empty corpus and
     * triggering a wasteful full re-crawl + re-embed.
     */
    public int loadOrIngest() throws IOException {
        if (corpusStore.exists()) {
            return load();
        }
        return ingest();
    }

    /** Fresh read (all sources) -> chunk -> embed (only NEW chunks) -> keyword index. */
    public int ingest() throws IOException {
        List<Document> pages = readers.stream()
            .flatMap(reader -> reader.get().stream())
            .toList();
        if (pages.isEmpty()) {
            log.warn("ingestion produced no documents from any DocumentReader");
            return 0;
        }
        List<Document> chunks = withStableIds(splitter.split(pages));
        log.info("ingest: {} pages -> {} chunks", pages.size(), chunks.size());

        // Incremental: skip only chunks whose position-stable id exists AND whose content
        // hash is unchanged. A chunk whose text changed (same id, different hash) or that was
        // ingested before the content hash existed is re-embedded and upserted into the
        // pgvector table (which is already durable, so there is nothing to save separately).
        Map<String, String> existingHashes = corpusStore.readAll().stream()
            .collect(Collectors.toMap(Document::getId,
                    doc -> String.valueOf(doc.getMetadata().getOrDefault(DocumentMeta.CONTENT_HASH, "")),
                    (a, b) -> a));
        List<Document> toEmbed = chunks.stream()
            .filter(chunk -> {
                String existing = existingHashes.get(chunk.getId());
                String current = String.valueOf(chunk.getMetadata().getOrDefault(DocumentMeta.CONTENT_HASH, ""));
                return existing == null || !existing.equals(current);
            })
            .toList();
        if (toEmbed.size() < chunks.size()) {
            log.info("ingest: {} of {} chunks already indexed, embedding only {} new",
                    chunks.size() - toEmbed.size(), chunks.size(), toEmbed.size());
        }

        int batchSize = Math.max(1, properties.embedBatchSize());
        for (int from = 0; from < toEmbed.size(); from += batchSize) {
            List<Document> batch = toEmbed.subList(from, Math.min(from + batchSize, toEmbed.size()));
            vectorStore.add(batch);
            log.debug("ingest: embedded batch of {} ({} / {})", batch.size(),
                    Math.min(from + batchSize, toEmbed.size()), toEmbed.size());
        }
        keywordIndex.addAll(chunks);
        return chunks.size();
    }

    /** Load a previously ingested corpus (no re-embedding) and rebuild the keyword index. */
    public int load() throws IOException {
        List<Document> chunks = corpusStore.readAll();
        keywordIndex.addAll(chunks);
        log.info("loaded {} chunks from store (embeddings reused, no API calls)", chunks.size());
        return chunks.size();
    }

    /**
     * Rewrites chunk ids as {@code sha256(version#sourceUrl + "#" + chunkIndex)} and stamps
     * each chunk with a content hash. The stable id makes re-ingestion an upsert; the content
     * hash is what lets incremental ingestion tell "unchanged" from "changed" for that id.
     */
    private List<Document> withStableIds(List<Document> chunks) {
        Map<String, Integer> perSourceIndex = new HashMap<>();
        List<Document> stable = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            String sourceUrl = String.valueOf(chunk.getMetadata().getOrDefault(DocumentMeta.SOURCE_URL, ""));
            String version = String.valueOf(chunk.getMetadata().getOrDefault(DocumentMeta.VERSION, ""));
            // Version-prefixed key so the same page across two Spring AI versions gets
            // distinct ids (no cross-version upsert collision).
            String sourceKey = version.isBlank() ? sourceUrl : version + "#" + sourceUrl;
            int index = perSourceIndex.merge(sourceKey, 1, Integer::sum) - 1;
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put(DocumentMeta.CHUNK_INDEX, index);
            metadata.put(DocumentMeta.CONTENT_HASH, sha256Hex(chunk.getText()));
            stable.add(Document.builder()
                    .id(sha256Hex(sourceKey + "#" + index))
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return stable;
    }

    /** Spring's {@code DigestUtils} only covers MD5, so hash with the JDK directly. */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
