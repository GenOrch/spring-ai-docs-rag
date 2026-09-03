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

The test suite covers retrieval (RRF / BM25 / hybrid), ingestion, rerank, audit and
service (query translation) components and runs without any external service.

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
2. Documentation is in sync with the code (see [`docs/README.md`](docs/README.md) for the doc
   map, and keep `docs/code-tour.md` accurate when you change the pipeline).

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
