package com.genorch.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/** Guards the shared query-translation behaviour used by both ask and eval paths. */
class QueryTranslatorTest {

    private final QueryTransformer transformer = mock(QueryTransformer.class);
    private final QueryTranslator translator = new QueryTranslator(transformer);

    private static Query query(String text) {
        return Query.builder().text(text).build();
    }

    @Test
    void englishQuerySkipsTranslation() {
        Query q = query("How does ChatClient work?");

        assertThat(translator.translate(q)).isSameAs(q);
        verify(transformer, never()).transform(q);
    }

    @Test
    void hanQueryIsTranslated() {
        Query q = query("如何在 Spring AI 中创建 ChatClient？");
        Query translated = query("How do I create a ChatClient in Spring AI?");
        when(transformer.transform(q)).thenReturn(translated);

        assertThat(translator.translate(q)).isSameAs(translated);
    }

    @Test
    void translationFailureFallsBackToOriginal() {
        Query q = query("Spring AI 支持哪些向量数据库？");
        when(transformer.transform(q)).thenThrow(new RuntimeException("boom"));

        assertThat(translator.translate(q)).isSameAs(q);
    }

    @Test
    void isHanDetectsChinese() {
        assertThat(QueryTranslator.isHan("ChatClient 是什么")).isTrue();
        assertThat(QueryTranslator.isHan("How does ChatClient work?")).isFalse();
        assertThat(QueryTranslator.isHan("")).isFalse();
        assertThat(QueryTranslator.isHan(null)).isFalse();
    }
}
