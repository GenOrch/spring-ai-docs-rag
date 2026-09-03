package com.genorch.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import com.genorch.rag.document.DocumentMeta;

/**
 * Guards the citation/source alignment invariant: the deduplication keeps the first
 * (highest-ranked) chunk per source URL, so the numbered context and the source list stay 1:1.
 */
class RagServiceTest {

    private static Document doc(String id, String url) {
        return Document.builder()
                .id(id)
                .text("text of " + id)
                .metadata(Map.of(DocumentMeta.SOURCE_URL, url))
                .build();
    }

    @Test
    void dedupsBySourceUrlKeepingHighestRankedFirst() {
        Document a1 = doc("a1", "https://x/page-a.html");
        Document a2 = doc("a2", "https://x/page-a.html");
        Document b = doc("b", "https://x/page-b.html");

        List<Document> deduped = RagService.dedupBySourceUrl(List.of(a1, a2, b));

        assertThat(deduped).extracting(Document::getId).containsExactly("a1", "b");
    }

    @Test
    void keepsBlankUrlChunksDistinct() {
        Document x = doc("x", "");
        Document y = doc("y", "");
        assertThat(RagService.dedupBySourceUrl(List.of(x, y))).hasSize(2);
    }
}
