/**
 * 可观测包：指标、追踪、审计的埋点集中地（对应 Grafana「服务运行状态 / 检索链路 / 模型成本 / 追踪」四个大盘）。
 *
 * <p>三块职责：
 * <ul>
 *   <li><b>指标</b>：{@code RagMetrics}（rag.* 计时器/计数器 + ObservationRegistry），覆盖检索/重排/模型调用；</li>
 *   <li><b>追踪关联</b>：{@code TraceIds} 工具 + {@code TraceIdFilter}，把 OTel traceId 注入 SLF4J MDC；</li>
 *   <li><b>审计日志</b>：见子包 {@code observability.audit}（两个切面 + 有界环形缓冲 + JSON lines 落盘，供 MCP {@code rag_logs} 消费）。</li>
 * </ul>
 *
 * <p>Spring AI 的 {@code gen_ai.*}（chat/embedding）与 Micrometer 的 {@code jvm_*}/{@code http_*} 由
 * 框架自动埋点，不在此包；本包只补框架没覆盖的 RAG 链路埋点、追踪关联与请求审计。
 */
package com.genorch.rag.observability;
