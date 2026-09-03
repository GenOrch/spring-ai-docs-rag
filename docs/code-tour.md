# 代码阅读地图 · Code Tour

> **读者**：第一次打开这个仓库、想快速看懂全链路的开发者。
> **解决什么**：用两条链路（写路径、读路径）把 33 个类串起来，告诉你「从哪个入口进来、每一步落在哪个类、哪个方法」，并标出接口化的「缝」。
> **相关文档**：[文档索引](README.md) · [架构](architecture.md) · [配置](configuration.md) · [可观测](../observability/README.md)
> **先看谁**：`web → service/ingest → retrieve/rerank → ingest/store`，就是下面两条链路的方向。

---

## 0. 一分钟总览

```
                         ┌─────────────────────────────────────────────┐
   POST /admin/ingest    │  写路径（摄取）：把文档变成可检索的 chunk      │
        │                │  reader → splitter → id → embed → 双索引      │
        ▼                └─────────────────────────────────────────────┘
  AdminController ──► IngestionService ──► [AsciiDocDocumentReader]
                                            [TokenTextSplitter]
                                            [VectorStore.add() 内嵌 embed]
                                            [LuceneKeywordIndex]
                                            [JdbcCorpusStore]

                         ┌─────────────────────────────────────────────┐
   POST /ask             │  读路径（问答）：把问题变成带引用的回答        │
        │                │  翻译 → 检索 → 重排 → 去重 → 编号 → 流式生成   │
        ▼                └─────────────────────────────────────────────┘
  AskController ──► RagService ──► [QueryTranslator]
                                   [HybridDocumentRetriever (版本下推)]
                                   [DashScopeRerankPostProcessor]
                                   [ChatClient 流式生成]
```

**接口化的「缝」（单一实现，可替换点）**：

| 缝 | 接口 | 实现 |
|---|---|---|
| 数据源 | `DocumentReader` | `AsciiDocDocumentReader` |
| 查询变换 | `QueryTransformer` | `TranslationQueryTransformer`（中→英） |
| 向量库 | `VectorStore` | `PgVectorStore`（PostgreSQL + pgvector） |
| 语料读回 | `CorpusStore` | `JdbcCorpusStore`（pgvector，SQL 读回） |
| 重排 | `DocumentPostProcessor` | `DashScopeRerankPostProcessor` |

---

## 1. 写路径（摄取 / 灌库）

入口：`POST /admin/ingest`，或启动时 `RAG_INGEST_ON_STARTUP=true` 自动触发。

### 1.1 `AdminController.ingest()` → `IngestionService.ingest()`

`web/AdminController.java` 只做协议转发，真正逻辑在 `ingest/IngestionService.java`。

`IngestionService.ingest()` 五步：

| 步 | 动作 | 落在 |
|---|---|---|
| ① | 读源（所有 `DocumentReader`） | `readers.stream().flatMap(r -> r.get())` |
| ② | 切分 + 稳定 id + content_hash | `withStableIds(splitter.split(pages))` → `sha256(version#sourceUrl#index)` + 打 `content_hash` |
| ③ | 增量检测 | `corpusStore.readAll()` 比对 id/`content_hash`，只留新增或变更的 chunk |
| ④ | 分批 embed + 存向量 | `vectorStore.add(batch)`（`VectorStore` 内部调 embedding，批=10，写进 pgvector） |
| ⑤ | 建 BM25 词法索引 | `keywordIndex.addAll(chunks)` |

> pgvector 表天然持久（向量与 chunk 文本都在表内），所以没有单独的 save/load 步骤。

### 1.2 读源：`AsciiDocDocumentReader`

`ingest/AsciiDocDocumentReader.java`：
- 遍历 `data/raw/spring-ai/{version}/spring-ai-docs/.../pages/**/*.adoc`，**目录名 = 版本号**（`2.0.1`/`1.1.8`），自动发现、打 `version` 标签。
- `cleanAsciiDoc(raw)` 剥掉 AsciiDoc 指令/代码块/行内标记，只留正文；`extractTitle(raw)` 取标题。
- 每个 `.adoc` → 一个 `Document`，metadata：`source_url` / `title` / `version`。

> 为什么读 GitHub 的 `.adoc` 源码而不是爬官网 HTML：同源、不封 ip、按 git tag 版本化、Apache 2.0 许可（类注释里有完整理由）。

### 1.3 持久化：`JdbcCorpusStore`

`ingest/store/`：
- `CorpusStore` 接口定义两个语料级操作：`exists()` / `readAll()`（Spring AI 的 `VectorStore` 不暴露这些）。
- `JdbcCorpusStore`：向量与 chunk 文本都已由 pgvector 持久化，`readAll()` 用原生 JDBC `SELECT id, content, metadata` 读回以重建 BM25、做增量摄取。

---

## 2. 读路径（问答）

入口：`POST /ask`。

### 2.1 `AskController.ask()`（`web/AskController.java`）

- 校验 question 非空 → 调 `RagService.ask(question, version)` 拿 `Answer(Flux<String> stream, List<Source> sources)`。
- 把 `Flux` 订成 SSE：逐 token 发，末尾发一个 `sources` 事件（引用 URL 列表）。

### 2.2 `RagService.ask()` —— 读路径唯一的编排点

`service/RagService.java`，六步（`ask` 方法里按顺序）：

| 步 | 动作 | 方法 |
|---|---|---|
| ① | 查询翻译（中文→英文，英文跳过） | `queryTranslator.translate(query)` → `QueryTranslator` 包装 `TranslationQueryTransformer` |
| ② | 混合检索（向量+BM25→RRF，版本下推） | `retriever.retrieve(retrievalQuery, version)` |
| ③ | 重排到 top5 | `rerank.process(retrievalQuery, docs)` |
| ④ | 按 URL 去重 | `dedupBySourceUrl(docs)` → 引用 `[n]` 与来源列表 1:1 对齐 |
| ⑤ | 拼编号上下文 | `buildContext(docs)` → `[n] title — url + 正文` |
| ⑥ | 流式生成 | `chatClient.prompt().system(...).user(question).stream().content()` |

> 为什么显式装配而不是用 `RetrievalAugmentationAdvisor`：为了把编号来源原样返回给客户端（类注释里的设计理由）。

### 2.3 检索：`HybridDocumentRetriever`（`retrieve/HybridDocumentRetriever.java`）

- 向量腿 `vectorHits(text)`：`vectorStore.similaritySearch(top20)`。
- 词法腿 `keywordHits(text)`：`keywordIndex.search(top20)`（`LuceneKeywordIndex`，内嵌 Lucene BM25，`EnglishAnalyzer`）。
- 融合 `rrfFuse(...)`：`RrfFusion.fuse(两条腿, k=60, top20)` —— RRF 公式 `score=Σ 1/(k+rank)`，按 id 去重。
- 每条腿独立 try/catch，失败降级为另一条腿（不整体崩）。

### 2.4 重排：`DashScopeRerankPostProcessor`（`rerank/`）

- 实现 `DocumentPostProcessor`，直连 DashScope 原生 rerank REST（`gte-rerank-v2`），因为 rerank 不走 OpenAI 兼容端点。
- 返回 `top_n`（5）条已按相关性排序的文档。

---

## 3. 重启恢复（不重 embed）

启动时 `ingest/StartupIngestionRunner` 调 `IngestionService.loadOrIngest()`：
- `corpusStore.exists()` 为真 → `load()`：`readAll()` + `keywordIndex.addAll()`（**0 次 API 调用**）。
- 否则 → `ingest()` 全量灌库。

---

## 4. 其余包一句话

| 包 | 一句话 |
|---|---|
| `config` | 装配 Bean + 绑配置（`RagConfig` / `RagProperties` / `ApiKeyStartupCheck`） |
| `document` | 元数据 key 常量（`DocumentMeta`） |
| `eval` | 检索/重排打分（`EvalService` → `GET /admin/eval`） |
| `mcp` | MCP 暴露层：`RagMcpTools`（4 个 `@McpTool`，读 `observability.AuditStore` + 驱动服务） |
| `observability` | 可观测埋点：`RagMetrics`（指标）+ `TraceIds`/`TraceIdFilter`（traceId）；审计切面/`AuditStore` 在子包 `observability.audit` |
| `web` | HTTP 入口 + /admin 与 /mcp 鉴权 |

每个包的 `package-info.java` 有更细的职责与依赖方向说明。
