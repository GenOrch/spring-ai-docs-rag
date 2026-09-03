package com.genorch.rag.eval;

import java.util.List;

/**
 * A single golden evaluation entry.
 *
 * <p>{@code mustContain} lists terms that at least one retrieved chunk should contain for
 * the question to count as a retrieval hit (a lightweight hit@k proxy that needs no LLM
 * judge). {@code sourceContains} lists URL fragments that should appear among the retrieved
 * sources, to verify the citation would point at the right page.
 */
public record GoldenQa(String question, List<String> mustContain, List<String> sourceContains) {
}
