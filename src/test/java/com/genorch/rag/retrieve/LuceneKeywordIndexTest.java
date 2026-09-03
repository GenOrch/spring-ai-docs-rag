package com.genorch.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Covers the BM25 "keyword leg" of hybrid retrieval, which had no test coverage at all.
 */
class LuceneKeywordIndexTest {

    @Test
    void matchesOnLexicalOverlapAndHonoursTopK() throws IOException {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex()) {
            index.addAll(List.of(doc("a", "ChatClient offers a fluent API for advisors"),
                    doc("b", "pgvector stores embeddings for similarity search"),
                    doc("c", "ChatClient builder configures chat options")));

            assertThat(index.search("ChatClient", 10))
                    .extracting(Document::getId)
                    .containsExactlyInAnyOrder("a", "c");

            assertThat(index.search("ChatClient", 1)).hasSize(1);
        }
    }

    @Test
    void returnsEmptyWhenNothingMatches() throws IOException {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex()) {
            index.addAll(List.of(doc("a", "ChatClient offers a fluent API")));

            assertThat(index.search("nonexistentterm", 10)).isEmpty();
            assertThat(index.search("   ", 10)).isEmpty();
        }
    }

    @Test
    void reindexingTheSameIdReplacesInsteadOfDuplicating() throws IOException {
        try (LuceneKeywordIndex index = new LuceneKeywordIndex()) {
            index.addAll(List.of(doc("a", "original content about ChatClient")));
            index.addAll(List.of(doc("a", "updated content about TokenTextSplitter")));

            assertThat(index.search("original", 10)).isEmpty();
            assertThat(index.search("TokenTextSplitter", 10))
                    .extracting(Document::getId)
                    .containsExactly("a");
        }
    }

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of()).build();
    }
}
