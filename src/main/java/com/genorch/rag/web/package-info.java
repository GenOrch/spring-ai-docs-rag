/**
 * Web 入口包：对外暴露 HTTP 接口。
 *
 * <p>{@code AskController} 提供 {@code POST /ask}（SSE 流式问答 + sources），
 * {@code AdminController} 提供 {@code POST /admin/ingest}（灌库）与 {@code GET /admin/eval}（评估），
 * {@code AdminAuthFilter} 给 /admin 和 /mcp 加鉴权。本包只做协议转换，业务都在 service/ingest/eval。
 */
package com.genorch.rag.web;
