package com.genorch.rag.eval;

import java.util.List;

/**
 * @param hitRate       share of questions whose expected term was retrieved (RRF-fused
 *                      top-k, before re-ranking)
 * @param rerankHitRate share of questions that still hit after re-ranking (the top-N that
 *                      actually feeds the LLM context)
 * @param sourceHitRate share of questions that retrieved the expected source document
 */
public record EvalReport(int total, int hits, double hitRate, int rerankHits, double rerankHitRate,
                         int sourceHits, double sourceHitRate, List<Item> items) {

    public record Item(String question, boolean hit, boolean rerankHit, boolean sourceHit,
                       List<String> topSources) {
    }
}
