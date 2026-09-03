# spring-ai-docs-rag

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-green.svg)](https://spring.io/projects/spring-ai)
[![CI](https://github.com/GenOrch/spring-ai-docs-rag/actions/workflows/ci.yml/badge.svg)](https://github.com/GenOrch/spring-ai-docs-rag/actions/workflows/ci.yml)

**English** · [简体中文](README.zh-CN.md)

A from-scratch RAG (Retrieval-Augmented Generation) project built with **Spring AI 2.0** on
**Spring Boot 4.1 / JDK 21**. It ingests the **versioned Spring AI reference** (the AsciiDoc
source behind docs.spring.io) and answers questions over it with hybrid retrieval + rerank +
cited generation — Chinese questions are translated to English first so they match the
English docs.

```
AsciiDoc (v2.0.1 / v1.1.8) → chunk → embed (DashScope)
  → hybrid retrieve (vector + BM25 + RRF) → rerank (gte-rerank-v2)
  → generate (Qwen, cited) → SSE stream + sources
```

Built against Spring AI's modular RAG abstractions (`DocumentReader`, `QueryTransformer`,
`DocumentRetriever`, `DocumentPostProcessor`) and its `VectorStore` interface.

## Features

- **Versioned corpus**: ingests Spring AI docs per version (`v2.0.1` + `v1.1.8`), and `/ask`
  accepts an optional `version` to filter answers.
- **Hybrid retrieval**: dense vector search + embedded Lucene BM25, fused with RRF.
- **Query translation**: Chinese questions are translated to English before retrieval (the
  answer still follows the question's language).
- **Re-ranking**: DashScope `gte-rerank-v2` via a custom `DocumentPostProcessor`.
- **Cited answers**: the LLM cites `[n]`, and `/ask` streams a final `sources` SSE event
  with the URL list.
- **Persistence**: embeddings and chunk text are persisted in pgvector (RDS) — restart
  reuses them (no re-embedding); the BM25 index is rebuilt from the vector table.
- **Vector store**: PostgreSQL + `pgvector` (shared, durable, in RDS).
- **Evaluation**: `GET /admin/eval` reports hit rate, rerank hit rate and source hit rate.
- **Demo page**: served at `/` (streaming chat UI with sources).
- **Observability**: Prometheus metrics (`gen_ai.*` + custom `rag.*`) at
  `/actuator/prometheus`, and OTel tracing to Jaeger — setup in [observability](observability/README.md).
- **MCP server**: an MCP server at `/mcp` with four tools (`rag_ask` / `rag_eval` /
  `rag_status` / `rag_logs`) for AI agents to inspect and drive the service.

## Requirements

- JDK 21, Maven 3.9+, Git 2.25+
- A DashScope (Alibaba Cloud) API key
- PostgreSQL + pgvector (RDS) — the vector store

Full configuration reference (every env var, download URLs, secrets setup):
[configuration](docs/configuration.md).

## Run (5 minutes)

```bash
# 1. Clone the corpus source (one-time; each directory under data/raw/spring-ai/ is a version)
git clone --depth 1 --filter=blob:none --sparse --branch v2.0.1 \
    https://github.com/spring-projects/spring-ai.git data/raw/spring-ai/2.0.1
git -C data/raw/spring-ai/2.0.1 sparse-checkout set spring-ai-docs
git clone --depth 1 --filter=blob:none --sparse --branch v1.1.8 \
    https://github.com/spring-projects/spring-ai.git data/raw/spring-ai/1.1.8
git -C data/raw/spring-ai/1.1.8 sparse-checkout set spring-ai-docs

# 2. Set the API key + database (macOS/Linux: export; Windows cmd: set)
export DASHSCOPE_API_KEY=sk-...
export RAG_INGEST_ON_STARTUP=true   # build the index on first startup
export PGVECTOR_URL=jdbc:postgresql://<host>:5432/rag   # pgvector (RDS) is the vector store
export PGVECTOR_USER=<user>
export PGVECTOR_PASSWORD=<password>

# 3. Run (serves the demo at http://localhost:8080)
mvn spring-boot:run                              # PostgreSQL + pgvector (RDS)
```

**How to know it worked** (each step is verifiable):

1. Startup log shows the index is ready — `startup index ready: 1426 chunks available`.
2. Ask a question — it streams tokens, then a final `sources` event with the cited URLs:
   ```bash
   curl -N -X POST http://localhost:8080/ask -H "Content-Type: application/json" \
     -d '{"question":"How does ChatClient work?","version":"2.0.1"}'
   ```
3. Evaluation — retrieval / rerank / source hit rates:
   ```bash
   curl -s http://localhost:8080/admin/eval
   ```
4. Open <http://localhost:8080> for the streaming chat UI — markdown rendering,
   clickable `[n]` citations and a source list:

   ![Demo page](docs/screenshots/demo-page.png)

If you skipped `RAG_INGEST_ON_STARTUP`, build the index manually with
`curl -X POST http://localhost:8080/admin/ingest` first.

**Smoke test** — a clean-room pass over the whole pipeline. Unlike the auto-ingest quickstart
above, start from an empty vector table (`TRUNCATE TABLE vector_store`) and ingest manually:

```bash
curl -X POST http://localhost:8080/admin/ingest   # full ingest: 249 pages -> 1426 chunks (~90s)
curl -X POST http://localhost:8080/admin/ingest   # incremental: "1426 of 1426 ... embedding only 0 new" (~3s)
curl -N -X POST http://localhost:8080/ask -H "Content-Type: application/json" \
  -d '{"question":"spring ai 2.0有哪些特色"}'           # cited answer + sources SSE
curl -s http://localhost:8080/admin/eval              # hit / rerank / source rates (~1.0 / 0.9 / 1.0)
```

[Docs index](docs/README.md) · [Code tour](docs/code-tour.md) · [Configuration](docs/configuration.md).

## MCP

The app also exposes an **MCP server** (Model Context Protocol) at `/mcp` (streamable HTTP),
so an AI agent can inspect and drive the service — not just hit the HTTP endpoints. Four tools:

- `rag_ask` — ask a question against the knowledge base (with citations + sources)
- `rag_eval` — run the retrieval/rerank evaluation
- `rag_status` — index status (chunk count, corpus versions)
- `rag_logs` — recent request audits + pipeline operation logs

Two AOP aspects (`observability/audit/RequestAuditAspect`, `observability/audit/OperationLogAspect`) record every request and
every pipeline method call into an in-memory ring buffer, correlated by the OTel trace id.

The `/mcp` endpoint is guarded by `AdminAuthFilter` like `/admin/*`: loopback callers are always
allowed; remote callers must send `X-Admin-Token`.

## Architecture

![Architecture](docs/architecture.svg)

Full design: [Architecture](docs/architecture.md) · [Code tour](docs/code-tour.md) · [Docs index](docs/README.md).

## Contributing

See [contributing](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE).
