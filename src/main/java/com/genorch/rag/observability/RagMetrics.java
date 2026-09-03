package com.genorch.rag.observability;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * Custom observability for the parts of the RAG pipeline that Spring AI does not observe:
 * its own {@code gen_ai.*} metrics cover chat / embedding, but the rerank call is a custom
 * REST call, so it is instrumented here.
 *
 * <p>Retrieve and rerank are wrapped in Micrometer {@code Observation}s: one abstraction
 * produces a trace span (Jaeger) AND a duration metric (Prometheus). Hit counts, rerank call
 * counts and document counts are plain counters because they are event counts, not durations.
 *
 * <p>Naming: {@code rag.*} is the project's own namespace, kept separate from Spring AI's
 * {@code gen_ai.*} (chat/embedding) and Micrometer's {@code jvm_*}/{@code http_*} (service
 * health). This maps 1:1 onto the Grafana dashboard split.
 */
@Component
public class RagMetrics {

    private final Counter vectorHits;
    private final Counter keywordHits;
    private final Counter vectorErrors;
    private final Counter keywordErrors;
    private final Counter rerankCalls;
    private final Counter rerankErrors;
    private final Counter rerankDocuments;
    private final AtomicReference<Double> evalHitRate = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> evalRerankHitRate = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> evalSourceHitRate = new AtomicReference<>(Double.NaN);
    private final ObservationRegistry observationRegistry;

    public RagMetrics(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.vectorHits = Counter.builder("rag.retrieve.vector.hits")
            .description("Documents returned by the vector leg")
            .register(meterRegistry);
        this.keywordHits = Counter.builder("rag.retrieve.keyword.hits")
            .description("Documents returned by the BM25 keyword leg")
            .register(meterRegistry);
        this.vectorErrors = Counter.builder("rag.retrieve.vector.errors")
            .description("Vector-leg failures that silently degraded to keyword-only")
            .register(meterRegistry);
        this.keywordErrors = Counter.builder("rag.retrieve.keyword.errors")
            .description("Keyword-leg failures that silently degraded to vector-only")
            .register(meterRegistry);
        this.rerankCalls = Counter.builder("rag.rerank.calls")
            .description("Number of rerank calls (gte-rerank-v2)")
            .register(meterRegistry);
        this.rerankErrors = Counter.builder("rag.rerank.errors")
            .description("Number of failed rerank calls (fell back to fused order)")
            .register(meterRegistry);
        this.rerankDocuments = Counter.builder("rag.rerank.documents")
            .description("Total documents submitted to the rerank model")
            .register(meterRegistry);
        // Evaluation result gauges: updated once per eval run (see updateEval). NaN until the
        // first evaluation, so Prometheus reports no sample until then.
        Gauge.builder("rag.eval.hit.rate", evalHitRate, AtomicReference::get)
            .description("Retrieval hit rate (hit@k over fused top-k)")
            .register(meterRegistry);
        Gauge.builder("rag.eval.rerank.hit.rate", evalRerankHitRate, AtomicReference::get)
            .description("Rerank hit rate (hit@k over reranked top-N)")
            .register(meterRegistry);
        Gauge.builder("rag.eval.source.hit.rate", evalSourceHitRate, AtomicReference::get)
            .description("Source hit rate (expected source retrieved)")
            .register(meterRegistry);
        this.observationRegistry = observationRegistry;
    }

    /** Records the latest evaluation result so it can be exposed as Prometheus gauges. */
    public void updateEval(double hitRate, double rerankHitRate, double sourceHitRate) {
        evalHitRate.set(hitRate);
        evalRerankHitRate.set(rerankHitRate);
        evalSourceHitRate.set(sourceHitRate);
    }

    public Counter vectorHits() {
        return vectorHits;
    }

    public Counter keywordHits() {
        return keywordHits;
    }

    public Counter vectorErrors() {
        return vectorErrors;
    }

    public Counter keywordErrors() {
        return keywordErrors;
    }

    public Counter rerankCalls() {
        return rerankCalls;
    }

    public Counter rerankErrors() {
        return rerankErrors;
    }

    public Counter rerankDocuments() {
        return rerankDocuments;
    }

    public ObservationRegistry observationRegistry() {
        return observationRegistry;
    }
}
