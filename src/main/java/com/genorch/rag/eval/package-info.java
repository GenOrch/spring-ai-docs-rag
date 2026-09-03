/**
 * 评估包：用 golden 问答集给检索/重排打分。
 *
 * <p>{@code EvalService} 逐题跑「检索 + 重排」，产出三个指标（hitRate / rerankHitRate /
 * sourceHitRate），不需要 LLM 当裁判。golden 集在 {@code classpath:eval/golden-qa.json}。
 */
package com.genorch.rag.eval;
