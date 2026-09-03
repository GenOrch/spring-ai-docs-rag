package com.genorch.rag.conversation;

/**
 * A chat conversation (session). Its {@code id} doubles as the {@code conversationId} sent to
 * {@code /ask}, so the same identifier drives both the UI and multi-turn memory.
 */
public record Conversation(String id, String title, String folderId, String version,
        long createdAt, long updatedAt) {
}
