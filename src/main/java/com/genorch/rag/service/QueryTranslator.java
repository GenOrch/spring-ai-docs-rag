package com.genorch.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;

/**
 * Translates a query into the corpus language (English) before retrieval.
 *
 * <p>Shared by the production ask path ({@link RagService}) and the evaluation harness
 * ({@code eval.EvalService}) so both measure the same, translated retrieval behaviour.
 * Han-script detection skips the LLM round-trip for queries that are already English, and
 * a translation failure falls back to the original text so the pipeline never hard-fails.
 */
@Component
public class QueryTranslator {

    private static final Logger log = LoggerFactory.getLogger(QueryTranslator.class);

    private final QueryTransformer transformer;

    public QueryTranslator(QueryTransformer transformer) {
        this.transformer = transformer;
    }

    /** Translates the query for retrieval, falling back to the original on failure. */
    public Query translate(Query query) {
        if (!isHan(query.text())) {
            // No CJK characters — assume the query is already in the corpus language (English),
            // so skip the translation LLM call entirely.
            return query;
        }
        try {
            return transformer.transform(query);
        }
        catch (RuntimeException e) {
            log.warn("query translation failed, using original text: {}", e.getMessage());
            return query;
        }
    }

    /** True when the text contains Han (Chinese) characters, i.e. it needs translation. */
    static boolean isHan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.codePoints()
            .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
}
