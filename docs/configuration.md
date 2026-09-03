# 配置参考 · Configuration

> **读者**：要调配置、要接环境的人。
> **解决什么**：从零跑通的每一步、每个环境变量、每个下载地址，以及密钥配置。
> **相关文档**：[文档索引](README.md) · [架构](architecture.md) · [代码阅读](code-tour.md) · [可观测](../observability/README.md)

## 1. 从零跑通（一步一步）

### 第 1 步 · 装工具（下载地址）

| 工具 | 版本 | 下载地址 |
|---|---|---|
| JDK | 21 | Microsoft OpenJDK <https://learn.microsoft.com/java/openjdk/download> 或 Eclipse Temurin <https://adoptium.net/> |
| Maven | 3.9+ | <https://maven.apache.org/download.cgi> |
| Git | 2.25+ | <https://git-scm.com/downloads> |

> 向量库是 PostgreSQL + pgvector（需数据库，数据在 RDS）。国内网络建议给 Maven 配阿里云镜像（`~/.m2/settings.xml`）。

### 第 2 步 · 拿 DashScope API Key

阿里云百炼控制台 → API-KEY 管理 → 创建 → 复制 `sk-...`。
<https://bailian.console.aliyun.com/>

> 一个 key 同时用于 chat（qwen-plus）、embedding（text-embedding-v3）、rerank（gte-rerank-v2），模型名都可用环境变量换。

### 第 3 步 · 克隆语料（AsciiDoc 数据源，一次即可）

克隆命令见 [根 README「Run」](../README.md#run-5-minutes)。

> 目录名即版本号（`2.0.1`/`1.1.8`），reader 自动发现。想加版本就再克隆一个目录。

### 第 4 步 · 配置

两种方式二选一（等价）：

- **环境变量**（Linux/macOS 用 `export`，Windows cmd 用 `set`）：
  ```bash
  export DASHSCOPE_API_KEY=sk-...
  export RAG_INGEST_ON_STARTUP=true
  export PGVECTOR_URL=jdbc:postgresql://<host>:5432/rag
  export PGVECTOR_USER=<user>
  export PGVECTOR_PASSWORD=<password>
  ```
- **`.env` 文件**（推荐，本地持久、无需每次 export）：复制 [`.env.example`](../.env.example) 为 `.env`（已 gitignore），填真实值；启动时由 `spring.config.import=optional:file:.env[.properties]` 自动加载。

### 第 5 步 · 启动（单一入口）

```bash
mvn spring-boot:run        # 首次启动建索引（RAG_INGEST_ON_STARTUP=true），demo 在 http://localhost:8080
```

### 第 6 步 · 验证

```bash
curl -N -X POST http://localhost:8080/ask -H "Content-Type: application/json" \
  -d '{"question":"How does ChatClient work?","version":"2.0.1"}'
```

## 2. 环境变量总表

| 变量 | 默认 | 作用 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 空（必填） | DashScope key；embedding / chat / rerank 共用 |
| `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容端点（`/v1` 不可省） |
| `DASHSCOPE_CHAT_MODEL` | `qwen-plus` | 生成模型 |
| `DASHSCOPE_EMBEDDING_MODEL` | `text-embedding-v3` | 向量模型（1024 维） |
| `DASHSCOPE_RERANK_MODEL` | `gte-rerank-v2` | 重排模型 |
| `DASHSCOPE_RERANK_ENDPOINT` | `.../services/rerank/text-rerank/text-rerank` | 重排原生端点 |
| `RAG_INGEST_ON_STARTUP` | `false` | 启动即建索引；否则 `POST /admin/ingest` 手动建 |
| `RAG_DATA_DIR` | `./data` | 语料目录（版本化 AsciiDoc 快照在 `data/raw/spring-ai/{version}`） |
| `RAG_EMBED_BATCH_SIZE` | `10` | 嵌入批量大小（DashScope 单请求上限 10，勿调大） |
| `RAG_TABLE_NAME` | `vector_store` | pgvector 向量表名 |
| `RAG_ADMIN_TOKEN` | 空 | `/admin` 和 `/mcp` 远程调用所需 token；空=拒绝远程调用（loopback 始终放行） |
| `RAG_ASK_TIMEOUT_MS` | `120000` | 单次 `/ask` SSE 流超时 |
| `RAG_MCP_AUDIT_BUFFER_SIZE` | `500` | 内存请求审计环形缓冲上限（`rag_logs` 读） |
| `RAG_MCP_OPERATION_BUFFER_SIZE` | `1000` | 内存链路操作日志环形缓冲上限 |
| `RAG_MCP_AUDIT_FILE_ENABLED` | `false` | 是否把请求审计追加落盘（JSONL，v1 无轮转） |
| `RAG_MCP_AUDIT_FILE` | `data/audit/requests.jsonl` | 审计落盘文件路径 |
| `PGVECTOR_URL` | `jdbc:postgresql://localhost:5432/rag` | pgvector 连接串 |
| `PGVECTOR_USER` / `PGVECTOR_PASSWORD` | `rag` / `rag` | pgvector 账号（真实值只进本地 `.env`） |
| `OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | OTel 追踪导出端点（Jaeger） |

## 3. 配置文件说明

| 文件 | 作用 |
|---|---|
| `src/main/resources/application.yml` | 唯一配置（AsciiDoc 语料 + pgvector），注释即文档 |
| `.env.example` | 完整环境变量模板（含默认值）；复制成 `.env`（gitignore）填真值 |

## 4. 密钥配置

- 仓库中所有配置值都用 `${ENV:默认}` 占位（见 `application.yml`），不含任何真实密钥。
- 真实的 `DASHSCOPE_API_KEY`、pgvector 连接信息放在本地 `.env`（已被 `.gitignore` 忽略；应用启动时经 `spring.config.import=optional:file:.env[.properties]` 自动加载，无需手动 export）或环境变量；`.env.example` 是含默认值的完整模板，不含任何真实密钥。
- 语料（clone 下来的 `.adoc`）与可选审计日志在 `data/`，同样被 `.gitignore` 忽略（向量在 pgvector 表，不在本地文件）。
- 如需发布语料快照（`.adoc` 源码），作为独立 Release 提供，附 Apache-2.0 署名，由使用者自备 API key 重新向量化。

## 5. 可观测

指标（Prometheus）与追踪（OTel→Jaeger）的接收端配置、下载地址、一步一步步骤、Grafana 大盘 JSON，全部在 [可观测](../observability/README.md)。
