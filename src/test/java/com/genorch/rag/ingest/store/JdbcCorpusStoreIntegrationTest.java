package com.genorch.rag.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.genorch.rag.config.RagProperties;
import com.genorch.rag.document.DocumentMeta;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Integration test: {@link JdbcCorpusStore} against a real PostgreSQL + pgvector instance.
 *
 * <p>Runs in CI (where Docker is available); skips automatically on a machine without Docker
 * so the local offline build is unaffected.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcCorpusStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("rag")
            .withUsername("rag")
            .withPassword("rag");

    static DataSource dataSource;

    static JdbcCorpusStore store;

    @BeforeAll
    static void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(config);

        // Minimal vector_store schema (id / content / metadata) — the columns JdbcCorpusStore reads.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE vector_store (
                        id TEXT PRIMARY KEY,
                        content TEXT,
                        metadata JSONB
                    )
                    """);
        }

        store = new JdbcCorpusStore(dataSource, properties("vector_store"));
    }

    @Test
    void persistsAndReadsBackChunks() throws Exception {
        // An empty table means no corpus yet.
        assertThat(store.exists()).isFalse();

        // Insert a chunk the way the vector store would (minus the embedding column).
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO vector_store (id, content, metadata) VALUES (?, ?, ?::jsonb)")) {
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
        JdbcCorpusStore missing = new JdbcCorpusStore(dataSource, properties("missing_table"));
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
}
