/**
 * 读路径编排包：把检索/重排串成「带引用的回答」。
 *
 * <p>{@code RagService} 是读路径唯一的编排点：查询翻译 → 检索（版本下推）→ 重排 →
 * 按 URL 去重 → 编号上下文 → 流式生成。显式装配（不用 {@code RetrievalAugmentationAdvisor}），
 * 是为了把编号来源原样返回给客户端做防幻觉引用。
 */
package com.genorch.rag.service;
