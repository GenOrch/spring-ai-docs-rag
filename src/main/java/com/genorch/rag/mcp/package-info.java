/**
 * MCP 暴露层：把服务能力与可观测数据通过 Model Context Protocol 暴露给 AI Agent。
 *
 * <p>{@code RagMcpTools} 用 {@code @McpTool} 声明 4 个工具（rag_ask / rag_eval / rag_status /
 * rag_logs），由 MCP server 的 annotation-scanner 自动发现。它只做「暴露 + 编排」：
 * 审计数据来自 {@code observability} 包的 {@code AuditStore}，业务逻辑来自 service/ingest/eval。
 */
package com.genorch.rag.mcp;
