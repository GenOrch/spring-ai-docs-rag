/**
 * 读路径（检索）包：把问题变成候选文档。
 *
 * <p>{@code HybridDocumentRetriever} 融合两条腿——向量检索（{@code VectorStore}）与
 * 词法检索（{@code LuceneKeywordIndex} 内嵌 BM25），再用 {@code RrfFusion}（RRF，k=60）
 * 融合去重。被 {@code service} 与 {@code eval} 调用。
 */
package com.genorch.rag.retrieve;
