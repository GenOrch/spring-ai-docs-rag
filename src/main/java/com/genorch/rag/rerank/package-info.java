/**
 * 重排包：把融合后的候选按相关性重排到 top-N。
 *
 * <p>{@code DashScopeRerankPostProcessor} 实现 Spring AI 的 {@code DocumentPostProcessor}，
 * 直连 DashScope 原生 rerank 接口（{@code gte-rerank-v2}）。被 {@code service} 与 {@code eval} 调用。
 */
package com.genorch.rag.rerank;
