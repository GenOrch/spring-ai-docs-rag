/**
 * 语料持久化缝：把「语料是否存在 / 读回 / 持久化」从摄取编排里抽出来。
 *
 * <p>{@code CorpusStore} 接口的 {@code JdbcCorpusStore} 实现（pgvector，SQL 读回）：语料向量与
 * 文本都在 {@code vector_store} 表内，无需单独的 save/load。
 */
package com.genorch.rag.ingest.store;
