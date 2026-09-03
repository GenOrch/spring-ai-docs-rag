package com.genorch.rag.conversation;

/**
 * One turn of a conversation. {@code role} is {@code "user"} or {@code "assistant"};
 * {@code sourcesJson} is a JSON array of citation sources (nullable, assistant only), stored
 * as text and parsed by the web layer when a client needs structured sources.
 */
public record Message(String id, String role, String content, String sourcesJson, long createdAt) {
}
