package com.genorch.rag.conversation;

import java.nio.file.Path;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SQLite-backed store for conversations, messages and folders.
 *
 * <p>Why SQLite: chat/session state is plain structured data with no vector-search need, so a
 * local, embedded, zero-ops database fits the single-node demo better than coupling it to the
 * pgvector RDS instance. It also replaces an earlier in-memory history,
 * so multi-turn memory survives restarts. The database file lives under {@code data/}
 * (gitignored).
 *
 * <p>Each operation opens a short-lived connection — SQLite opens in well under a millisecond
 * and the demo is single-user, so a pooled datasource would be overkill.
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private static final String DEFAULT_DB_URL = "jdbc:sqlite:" + Path.of("data", "conversations.db").toAbsolutePath();

    private final String dbUrl;

    public ConversationStore() {
        this(DEFAULT_DB_URL);
    }

    /** Package-private for tests: point at a temp database file. */
    ConversationStore(String dbUrl) {
        this.dbUrl = dbUrl;
        initSchema();
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(dbUrl);
        // Wait up to 5s on a write lock instead of failing immediately with "database is locked".
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void initSchema() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS folder (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS conversation (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL DEFAULT '新对话',
                        folder_id TEXT,
                        version TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS message (
                        id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sources TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_message_conv ON message(conversation_id, created_at)");
        }
        catch (SQLException e) {
            log.warn("failed to initialize SQLite conversation schema: {}", e.getMessage());
        }
    }

    // ---- folders ----

    public List<Folder> folders() {
        List<Folder> list = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, name FROM folder ORDER BY created_at, rowid")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Folder(rs.getString("id"), rs.getString("name")));
                }
            }
        }
        catch (SQLException e) {
            log.warn("folders() failed: {}", e.getMessage());
        }
        return list;
    }

    public Folder addFolder(String name) {
        Folder folder = new Folder(UUID.randomUUID().toString(), name);
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO folder (id, name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, folder.id());
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (SQLException e) {
            log.warn("addFolder failed: {}", e.getMessage());
        }
        return folder;
    }

    public void deleteFolder(String id) {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM folder WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE conversation SET folder_id = NULL WHERE folder_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
        catch (SQLException e) {
            log.warn("deleteFolder failed: {}", e.getMessage());
        }
    }

    // ---- conversations ----

    public List<Conversation> conversations() {
        List<Conversation> list = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, title, folder_id, version, created_at, updated_at FROM conversation "
                        + "ORDER BY updated_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapConversation(rs));
                }
            }
        }
        catch (SQLException e) {
            log.warn("conversations() failed: {}", e.getMessage());
        }
        return list;
    }

    public Conversation conversation(String id) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, title, folder_id, version, created_at, updated_at FROM conversation WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapConversation(rs) : null;
            }
        }
        catch (SQLException e) {
            log.warn("conversation() failed: {}", e.getMessage());
        }
        return null;
    }

    public Conversation addConversation(String id, String title) {
        long now = System.currentTimeMillis();
        String t = title == null || title.isBlank() ? "新对话" : title;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conversation (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, t);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            log.warn("addConversation failed: {}", e.getMessage());
        }
        return new Conversation(id, t, null, null, now, now);
    }

    public void renameConversation(String id, String title) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE conversation SET title = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, title);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            log.warn("renameConversation failed: {}", e.getMessage());
        }
    }

    public void moveConversation(String id, String folderId) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE conversation SET folder_id = ?, updated_at = ? WHERE id = ?")) {
            if (folderId == null || folderId.isBlank()) {
                ps.setNull(1, Types.VARCHAR);
            }
            else {
                ps.setString(1, folderId);
            }
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            log.warn("moveConversation failed: {}", e.getMessage());
        }
    }

    /** Updates the last-used version and bumps {@code updated_at} (e.g. after a question). */
    public void touchConversation(String id, String version) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE conversation SET version = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, version);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            log.warn("touchConversation failed: {}", e.getMessage());
        }
    }

    public void deleteConversation(String id) {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM message WHERE conversation_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM conversation WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
        catch (SQLException e) {
            log.warn("deleteConversation failed: {}", e.getMessage());
        }
    }

    // ---- messages ----

    public List<Message> messages(String conversationId) {
        List<Message> list = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, role, content, sources, created_at FROM message "
                        + "WHERE conversation_id = ? ORDER BY created_at, rowid")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapMessage(rs));
                }
            }
        }
        catch (SQLException e) {
            log.warn("messages() failed: {}", e.getMessage());
        }
        return list;
    }

    /** The most recent {@code limit} messages, oldest first (for multi-turn memory). */
    public List<Message> recentMessages(String conversationId, int limit) {
        List<Message> all = messages(conversationId);
        int n = Math.min(limit, all.size());
        return all.subList(all.size() - n, all.size());
    }

    public void addMessage(String conversationId, String role, String content, String sourcesJson) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO message (id, conversation_id, role, content, sources, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, conversationId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, sourcesJson);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
        catch (SQLException e) {
            log.warn("addMessage failed: {}", e.getMessage());
        }
    }

    private static Conversation mapConversation(ResultSet rs) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getString("title"), rs.getString("folder_id"),
                rs.getString("version"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static Message mapMessage(ResultSet rs) throws SQLException {
        return new Message(rs.getString("id"), rs.getString("role"), rs.getString("content"),
                rs.getString("sources"), rs.getLong("created_at"));
    }
}
