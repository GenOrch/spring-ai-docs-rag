package com.genorch.rag.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import com.genorch.rag.config.RagProperties;
import com.genorch.rag.document.DocumentMeta;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Integration test: {@link JdbcCorpusStore} against a <em>real</em> PostgreSQL database.
 *
 * <p>This project deploys to a physical PostgreSQL / pgvector instance (RDS) rather than a
 * Docker container, so the test talks directly to the database configured in {@code .env} (or
 * the process environment) via {@code PGVECTOR_URL} / {@code PGVECTOR_USER} /
 * {@code PGVECTOR_PASSWORD}. When no target database is configured — for example in CI — the
 * whole class is skipped, so the regular build stays green without Docker.
 *
 * <p>It uses a dedicated table name ({@code corpus_store_it}) and drops it afterwards, so it
 * never touches the production {@code vector_store} table.
 */
class JdbcCorpusStoreIntegrationTest {

    private static final String TABLE = "corpus_store_it";

    private static DataSource dataSource;

    private static JdbcCorpusStore store;

    @BeforeAll
    static void setUp() throws Exception {
        String url = envOrDotEnv("PGVECTOR_URL");
        // No target database configured -> skip (CI, or a machine without a real PostgreSQL).
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "PGVECTOR_URL not configured — skipping integration test (no real PostgreSQL target)");

        String user = envOrDotEnv("PGVECTOR_USER");
        String password = envOrDotEnv("PGVECTOR_PASSWORD");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user == null ? "" : user);
        config.setPassword(password == null ? "" : password);
        config.setConnectionTimeout(3000);
        dataSource = new HikariDataSource(config);

        // Dedicated test table (id / content / metadata) — the three columns JdbcCorpusStore reads.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("""
                    CREATE TABLE %s (
                        id TEXT PRIMARY KEY,
                        content TEXT,
                        metadata JSONB
                    )
                    """.formatted(TABLE));
        }

        store = new JdbcCorpusStore(dataSource, properties(TABLE));
    }

    @AfterAll
    static void tearDown() {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        }
        catch (Exception ignored) {
            // Best-effort cleanup: the test table is disposable.
        }
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    @Test
    void persistsAndReadsBackChunks() throws Exception {
        // An empty table means no corpus yet.
        assertThat(store.exists()).isFalse();

        // Insert a chunk the way the vector store would (minus the embedding column).
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + TABLE + " (id, content, metadata) VALUES (?, ?, ?::jsonb)")) {
            statement.setString(1, "chunk-1");
            statement.setString(2, "ChatClient offers a fluent API");
            statement.setString(3, "{\"source_url\":\"https://docs.spring.io/spring-ai/reference/api/chatclient.html\","
                    + "\"title\":\"Chat Client API\",\"version\":\"2.0.1\"}");
            statement.executeUpdate();
        }

        assertThat(store.exists()).isTrue();

        List<Document> chunks = store.readAll();
        assertThat(chunks).hasSize(1);
        Document chunk = chunks.get(0);
        assertThat(chunk.getId()).isEqualTo("chunk-1");
        assertThat(chunk.getText()).isEqualTo("ChatClient offers a fluent API");
        assertThat(chunk.getMetadata())
                .containsEntry(DocumentMeta.SOURCE_URL,
                        "https://docs.spring.io/spring-ai/reference/api/chatclient.html")
                .containsEntry(DocumentMeta.VERSION, "2.0.1");
    }

    @Test
    void existsIsFalseWhenTableDoesNotExist() throws Exception {
        // 42P01 (undefined_table) is mapped to "not ingested yet", not an error.
        JdbcCorpusStore missing = new JdbcCorpusStore(dataSource, properties(TABLE + "_missing"));
        assertThat(missing.exists()).isFalse();
    }

    private static RagProperties properties(String tableName) {
        return new RagProperties(
                false,
                "./target/test-data",
                10,
                tableName,
                "system prompt",
                "",
                120_000L,
                new RagProperties.Chunk(500, 100, 10, 10000),
                new RagProperties.Retrieve(20, 20, 20, 60),
                new RagProperties.Rerank(true, "http://rerank", "gte-rerank-v2", 5),
                new RagProperties.Mcp(500, 1000, false, "target/test-audit.jsonl"));
    }

    /** Reads a value from the process environment first, then falls back to the project {@code .env}. */
    private static String envOrDotEnv(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return readDotEnv().get(key);
    }

    /** Parses {@code .env} (KEY=VALUE, {@code #} comments, optional quotes) without any framework. */
    private static Map<String, String> readDotEnv() {
        Map<String, String> result = new HashMap<>();
        Path dotEnv = Path.of(".env");
        if (!Files.isRegularFile(dotEnv)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(dotEnv)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        catch (IOException ignored) {
            // Return whatever was read; callers treat a missing key as "not configured".
        }
        return result;
    }
}
