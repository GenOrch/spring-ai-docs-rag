/**
 * 写路径（摄取）包：把文档源变成可检索的 chunk。
 *
 * <p>三个角色：
 * <ul>
 *   <li>读取器 {@code AsciiDocDocumentReader}（版本化本地 AsciiDoc 语料）；</li>
 *   <li>编排 {@code IngestionService}（读源 → 切分 → 稳定 id + content_hash → 分批 embed → 双索引）；</li>
 *   <li>启动灌库 {@code StartupIngestionRunner}。</li>
 * </ul>
 *
 * <p>读取器以 {@code List<DocumentReader>} 注入，所以新增数据源 = 新增一个 reader Bean，摄取逻辑零改动。
 * 语料持久化委托给 {@code ingest.store} 包。
 */
package com.genorch.rag.ingest;
