package com.genorch.rag.retrieve;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

/**
 * Reciprocal Rank Fusion over one or more ranked document lists.
 *
 * <p>{@code score(d) = sum(1 / (k + rank_i(d)))} where {@code rank_i(d)} is the 1-based
 * position of {@code d} in list {@code i}. Documents are deduplicated by their id; a
 * document ranked highly in several lists (e.g. both the vector and BM25 legs) receives
 * a higher fused score than one ranked highly in only a single list.
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * @param rankedLists ranked document lists, each ordered best-first
     * @param k           the RRF smoothing constant (60 is a common default)
     * @param topN        maximum number of fused results to return
     */
    public static List<Document> fuse(List<List<Document>> rankedLists, int k, int topN) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();
        for (List<Document> ranked : rankedLists) {
            int rank = 1;
            for (Document document : ranked) {
                String id = document.getId();
                byId.putIfAbsent(id, document);
                scores.merge(id, 1.0 / (k + rank), Double::sum);
                rank++;
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(topN)
            .map(entry -> byId.get(entry.getKey()))
            .toList();
    }
}
