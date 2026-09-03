/**
 * 审计日志子包：把「每次请求 + 每个链路方法调用」记成结构化记录。
 *
 * <p>两个切面 + 一个存储 + 记录/工具：
 * <ul>
 *   <li>{@code RequestAuditAspect} —— 入口切面（HTTP / MCP 工具调用），每请求一条 {@code RequestAudit}；</li>
 *   <li>{@code OperationLogAspect} —— 链路切面（service/ingest/retrieve/rerank），每方法一条 {@code OperationLog}；</li>
 *   <li>{@code AuditStore} —— 有界环形缓冲 + JSON lines 落盘，供 MCP {@code rag_logs} 消费。</li>
 * </ul>
 *
 * <p>traceId 取自父包 {@code observability.TraceIds}，与指标/追踪共用同一个关联键。
 */
package com.genorch.rag.observability.audit;
