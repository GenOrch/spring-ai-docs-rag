package com.genorch.rag.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.web.client.RestClient;

import com.genorch.rag.config.RagProperties.Rerank;
import com.genorch.rag.observability.RagMetrics;

/** Guards the rerank-disabled fallback: it must still honour top_n, not balloon to top-20. */
class DashScopeRerankPostProcessorTest {

    private static Document doc(String id) {
        return Document.builder().id(id).text("text " + id).metadata(Map.of()).build();
    }

    @Test
    void disabledRerankStillTruncatesToTopN() {
        Rerank config = new Rerank(false, "http://rerank", "gte-rerank-v2", 2);
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        DashScopeRerankPostProcessor processor = new DashScopeRerankPostProcessor(
                config, "key", builder, mock(RagMetrics.class));

        List<Document> docs = List.of(doc("a"), doc("b"), doc("c"), doc("d"));
        List<Document> result = processor.process(Query.builder().text("q").build(), docs);

        assertThat(result).extracting(Document::getId).containsExactly("a", "b");
    }
}
