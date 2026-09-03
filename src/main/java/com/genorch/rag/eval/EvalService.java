package com.genorch.rag.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.genorch.rag.document.DocumentMeta;
import com.genorch.rag.observability.RagMetrics;
import com.genorch.rag.retrieve.HybridDocumentRetriever;
import com.genorch.rag.rerank.DashScopeRerankPostProcessor;
import com.genorch.rag.service.QueryTranslator;

/**
 * Minimal, honest evaluation harness. For each golden question it runs the hybrid retriever
 * and the re-ranker, then reports both retrieval recall (hit@k over the fused top-k) and
 * post-rerank precision (hit@k over the re-ranked top-N that actually feeds the LLM). This
 * gives a baseline that later tuning (chunk size, top-k, rerank) can be measured against,
 * without needing an LLM judge.
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final HybridDocumentRetriever retriever;
    private final DashScopeRerankPostProcessor rerank;
    private final ResourceLoader resourceLoader;
    private final RagMetrics metrics;
    private final QueryTranslator queryTranslator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvalService(HybridDocumentRetriever retriever, DashScopeRerankPostProcessor rerank,
            ResourceLoader resourceLoader, RagMetrics metrics, QueryTranslator queryTranslator) {
        this.retriever = retriever;
        this.rerank = rerank;
        this.resourceLoader = resourceLoader;
        this.metrics = metrics;
        this.queryTranslator = queryTranslator;
    }

    public EvalReport evaluate() throws IOException {
        List<GoldenQa> qas = loadGoldenQa();
        List<EvalReport.Item> items = new ArrayList<>();
        int hits = 0;
        int rerankHits = 0;
        int sourceHits = 0;
        for (GoldenQa qa : qas) {
            Query query = Query.builder().text(qa.question()).build();
            // Mirror the production /ask path: translate before retrieval and rerank so
            // Chinese questions exercise the same (translated) behaviour the service uses,
            // instead of being scored against a degraded, untranslated retrieval.
            Query retrievalQuery = queryTranslator.translate(query);
            List<Document> retrieved = retriever.retrieve(retrievalQuery);
            List<Document> reranked = rerank.process(retrievalQuery, retrieved);
            boolean hit = isHit(qa, retrieved);
            boolean rerankHit = isHit(qa, reranked);
            boolean sourceHit = isSourceHit(qa, retrieved);
            if (hit) {
                hits++;
            }
            if (rerankHit) {
                rerankHits++;
            }
            if (sourceHit) {
                sourceHits++;
            }
            items.add(new EvalReport.Item(qa.question(), hit, rerankHit, sourceHit, topSources(reranked)));
        }
        double hitRate = qas.isEmpty() ? 0.0 : (double) hits / qas.size();
        double rerankHitRate = qas.isEmpty() ? 0.0 : (double) rerankHits / qas.size();
        double sourceHitRate = qas.isEmpty() ? 0.0 : (double) sourceHits / qas.size();
        log.info("eval: n={} hit={}/{} rerank={}/{} source={}/{}", qas.size(),
                hits, qas.size(), rerankHits, qas.size(), sourceHits, qas.size());
        metrics.updateEval(hitRate, rerankHitRate, sourceHitRate);
        return new EvalReport(qas.size(), hits, hitRate, rerankHits, rerankHitRate, sourceHits, sourceHitRate, items);
    }

    private boolean isHit(GoldenQa qa, List<Document> docs) {
        // AND semantics: every mustContain term must appear somewhere in the retrieved set.
        // (A question asking for "pgvector AND Milvus" should only pass when both are present.)
        String corpus = docs.stream()
            .map(doc -> doc.getText() == null ? "" : doc.getText().toLowerCase())
            .collect(Collectors.joining("\n"));
        for (String term : qa.mustContain()) {
            if (!corpus.contains(term.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether the expected source document was retrieved, using the previously unused
     * {@code sourceContains} field. Content hits alone can be satisfied by a chunk that merely
     * mentions the term; this also verifies the citation would point at the right page.
     */
    private boolean isSourceHit(GoldenQa qa, List<Document> docs) {
        List<String> expected = qa.sourceContains();
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        for (Document doc : docs) {
            String url = String.valueOf(doc.getMetadata().getOrDefault(DocumentMeta.SOURCE_URL, "")).toLowerCase();
            for (String fragment : expected) {
                if (url.contains(fragment.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> topSources(List<Document> docs) {
        return docs.stream()
            .map(doc -> String.valueOf(doc.getMetadata().getOrDefault(DocumentMeta.SOURCE_URL, "?")))
            .distinct()
            .toList();
    }

    private List<GoldenQa> loadGoldenQa() throws IOException {
        try (InputStream in = resourceLoader.getResource("classpath:eval/golden-qa.json").getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<GoldenQa>>() {
            });
        }
    }
}
