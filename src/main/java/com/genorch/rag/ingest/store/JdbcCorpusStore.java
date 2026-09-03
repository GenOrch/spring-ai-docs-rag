package com.genorch.rag.ingest.store;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genorch.rag.config.RagProperties;

/**
 * {@link CorpusStore} backed by the pgvector table: vectors and chunk text live in the
 * {@code vector_store} table, so the corpus is read straight back with SQL to rebuild the
 * in-memory BM25 index on startup and to power incremental ingestion.
 *
 * <p>Deliberately uses plain JDBC against the JDK's {@link DataSource} instead of
 * {@code JdbcTemplate}: it only reads three columns (id / content / metadata).
 */
@Component
public class JdbcCorpusStore implements CorpusStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcCorpusStore.class);

    private final DataSource dataSource;
    private final RagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcCorpusStore(DataSource dataSource, RagProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public boolean exists() throws IOException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + tableName());
                ResultSet rs = statement.executeQuery()) {
            return rs.next() && rs.getLong(1) > 0;
        }
        catch (SQLException e) {
            // 42P01 = undefined_table: the corpus has simply not been ingested yet.
            // Anything else (connection failures, auth errors, ...) means we cannot tell
            // whether a corpus exists — fail loudly instead of treating an unreachable
            // database as "empty", which would trigger a wasteful full re-crawl + re-embed.
            if ("42P01".equals(e.getSQLState())) {
                return false;
            }
            throw new IOException("cannot determine whether corpus exists in " + tableName()
                    + " (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    @Override
    public List<Document> readAll() throws IOException {
        String sql = "SELECT id, content, metadata FROM " + tableName();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {

            List<Document> documents = new ArrayList<>();
            while (rs.next()) {
                documents.add(Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(readMetadata(rs.getString("metadata")))
                        .build());
            }
            log.info("corpus: read {} chunks from {}", documents.size(), tableName());
            return documents;
        }
        catch (SQLException e) {
            throw new IOException("failed to read corpus from " + tableName(), e);
        }
    }

    private Map<String, Object> readMetadata(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private String tableName() {
        String table = properties.tableName();
        return (table == null || table.isBlank()) ? "vector_store" : table;
    }
}
