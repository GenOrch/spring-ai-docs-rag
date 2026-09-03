# Contributing

Thanks for your interest in contributing to `spring-ai-docs-rag`.

## Prerequisites

- JDK 21
- Maven 3.9+

## Build and test

```bash
# Run the unit tests (offline; no API key or database required)
mvn -o test

# Build the executable jar
mvn -DskipTests package
```

The unit tests cover retrieval (RRF / BM25 / hybrid), ingestion, rerank, audit,
conversation (SQLite persistence) and service (query translation) components and run
without any external service.

One integration test, `JdbcCorpusStoreIntegrationTest`, exercises `JdbcCorpusStore` against a
real PostgreSQL database. It reads `PGVECTOR_URL` / `PGVECTOR_USER` / `PGVECTOR_PASSWORD` from
`.env` (or the environment) and skips automatically when no target database is configured
(e.g. in CI). It uses a throwaway `corpus_store_it` table and never touches the production
`vector_store` table.

## Code style

- Constructor injection and immutable records over mutable beans.
- One responsibility per class; every package has a `package-info.java` that states its job
  and dependency direction.
- Javadoc explains *why* a design decision was made, not just *what* the code does.

## Commit convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` a new capability
- `fix:` a bug fix
- `docs:` documentation only
- `refactor:` restructuring without behaviour change
- `chore:` build/config/maintenance

## Before opening a pull request

1. `mvn -o test` passes.
2. Documentation is in sync with the code (see the [Docs index](docs/README.md) for the doc
   map, and keep `docs/code-tour.md` accurate when you change the pipeline).

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
