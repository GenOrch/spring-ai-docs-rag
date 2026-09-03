package com.genorch.rag.retrieve;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import io.micrometer.observation.Observation;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.genorch.rag.config.RagProperties.Retrieve;
import com.genorch.rag.observability.RagMetrics;

/**
 * Hybrid {@link DocumentRetriever} that fuses two complementary recall signals:
 *
 * <ol>
 * <li>semantic search via the {@link VectorStore} (dense embeddings)</li>
 * <li>lexical search via {@link LuceneKeywordIndex} (BM25)</li>
 * </ol>
 *
 * <p>The two result lists are merged with Reciprocal Rank Fusion (RRF):
 * {@code score(d) = sum(1 / (k + rank_i(d)))}. This is the key differentiator over the
 * tutorial-level "pure vector" RAG, and is where the earlier "metadata-contract +
 * non-vector retrieval" experience is completed with a proper vector leg.
 */
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    /** Restricts the version filter to a safe token, so it cannot break the filter expression. */
    private static final Pattern VERSION_PATTERN = Pattern.compile("[\\w.-]+");

    private final VectorStore vectorStore;
    private final LuceneKeywordIndex keywordIndex;
    private final Retrieve config;
    private final RagMetrics metrics;

    public HybridDocumentRetriever(VectorStore vectorStore, LuceneKeywordIndex keywordIndex, Retrieve config,
            RagMetrics metrics) {
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.config = config;
        this.metrics = metrics;
    }

    @Override
    public List<Document> retrieve(Query query) {
        return retrieve(query, null);
    }

    /** Retrieves with an optional version filter pushed down into both legs. */
    public List<Document> retrieve(Query query, String version) {
        return Observation.createNotStarted("rag.retrieve", metrics.observationRegistry()).observe(() -> {
            String text = query.text();
            // Validate the version once so both legs agree: an invalid version is ignored
            // (retrieve across all versions) instead of the vector leg ignoring it while the
            // keyword leg applies an exact match that silently returns nothing.
            String safeVersion = sanitizeVersion(version);
            List<Document> vectorHits = vectorHits(text, safeVersion);
            metrics.vectorHits().increment(vectorHits.size());
            List<Document> keywordHits = keywordHits(text, safeVersion);
            metrics.keywordHits().increment(keywordHits.size());
            List<Document> fused = rrfFuse(vectorHits, keywordHits);
            log.debug("hybrid retrieve: query='{}' version={} vector={} keyword={} fused={}",
                    text, safeVersion, vectorHits.size(), keywordHits.size(), fused.size());
            return fused;
        });
    }

    /**
     * Returns the version if it is a safe token, or {@code null} (meaning "no filter") when it
     * is blank or fails the whitelist. A single validation point keeps the vector and keyword
     * legs consistent for the same input and prevents the filter expression from being broken.
     */
    static String sanitizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        if (VERSION_PATTERN.matcher(version).matches()) {
            return version;
        }
        log.warn("ignoring invalid version filter: {}", version);
        return null;
    }

    private List<Document> vectorHits(String text, String version) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(text)
                .topK(config.vectorTopK());
            if (version != null) {
                builder.filterExpression("version == '" + version + "'");
            }
            return vectorStore.similaritySearch(builder.build());
        }
        catch (RuntimeException e) {
            metrics.vectorErrors().increment();
            log.warn("vector search failed, falling back to keyword-only: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> keywordHits(String text, String version) {
        try {
            return keywordIndex.search(text, config.keywordTopK(), version);
        }
        catch (IOException e) {
            metrics.keywordErrors().increment();
            log.warn("keyword search failed, falling back to vector-only: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> rrfFuse(List<Document> vectorHits, List<Document> keywordHits) {
        return RrfFusion.fuse(List.of(vectorHits, keywordHits), config.rrfK(), config.fusionTopN());
    }
}
