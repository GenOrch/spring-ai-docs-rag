package com.genorch.rag.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.genorch.rag.config.RagProperties.Rerank;
import com.genorch.rag.observability.RagMetrics;

import io.micrometer.observation.Observation;

/**
 * Re-ranks the fused candidate documents with DashScope's {@code gte-rerank-v2} model,
 * implemented against Spring AI's {@link DocumentPostProcessor} abstraction.
 *
 * <p>DashScope rerank is not exposed over the OpenAI-compatible endpoint, so this class
 * calls the native DashScope rerank REST API directly via {@link RestClient}. This is a
 * deliberate differentiator: it shows how to extend the framework beyond its built-in
 * providers while still plugging into the standard RAG pipeline.
 */
public class DashScopeRerankPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankPostProcessor.class);

    private final Rerank config;
    private final RestClient restClient;
    private final String apiKey;
    private final RagMetrics metrics;

    public DashScopeRerankPostProcessor(Rerank config, String apiKey, RestClient.Builder restClientBuilder,
            RagMetrics metrics) {
        this.config = config;
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.build();
        this.metrics = metrics;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        if (!config.enabled()) {
            // Rerank is off, but still honour the configured top_n so the downstream context
            // builder sees the same result size as the enabled path (fused top_n, unranked).
            return truncate(documents);
        }
        metrics.rerankCalls().increment();
        metrics.rerankDocuments().increment(documents.size());
        return Observation.createNotStarted("rag.rerank", metrics.observationRegistry()).observe(() -> {
            try {
                return doRerank(query.text(), documents);
            }
            catch (RuntimeException e) {
                metrics.rerankErrors().increment();
                log.warn("rerank failed, returning fused order (truncated) unchanged: {}", e.getMessage());
                return truncate(documents);
            }
        });
    }

    /**
     * Truncates to the configured {@code top_n} so every return path (success, API error, empty
     * response) yields the same result size — the downstream context builder assumes top_n.
     */
    private List<Document> truncate(List<Document> documents) {
        int n = Math.min(config.topN(), documents.size());
        return n >= documents.size() ? documents : new ArrayList<>(documents.subList(0, n));
    }

    private List<Document> doRerank(String queryText, List<Document> documents) {
        List<String> texts = documents.stream().map(Document::getText).toList();
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "input", Map.of("query", queryText, "documents", texts),
                "parameters", Map.of("return_documents", false, "top_n", config.topN()));

        RerankResponse response = restClient.post()
            .uri(config.endpoint())
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(RerankResponse.class);

        if (response == null || response.output() == null || response.output().results() == null) {
            return truncate(documents);
        }

        // DashScope returns results already ordered by relevance and limited to top_n.
        List<Document> reranked = new ArrayList<>();
        for (RerankResult result : response.output().results()) {
            int index = result.index();
            if (index >= 0 && index < documents.size()) {
                reranked.add(documents.get(index));
            }
        }
        // An empty/unusable response falls back to the fused order, truncated to top_n.
        return reranked.isEmpty() ? truncate(documents) : reranked;
    }

    // --- DashScope rerank response shape (only the fields we consume are mapped) ---

    record RerankResponse(Output output) {
    }

    record Output(List<RerankResult> results) {
    }

    /** Index into the original candidate list; results arrive already sorted by relevance. */
    record RerankResult(int index) {
    }
}
