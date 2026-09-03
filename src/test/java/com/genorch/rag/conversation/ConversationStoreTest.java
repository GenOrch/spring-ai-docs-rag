package com.genorch.rag.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the SQLite conversation store: conversation CRUD (with cascade) + message ordering. */
class ConversationStoreTest {

    @TempDir
    Path dir;

    private ConversationStore store;

    @BeforeEach
    void setUp() {
        store = new ConversationStore("jdbc:sqlite:" + dir.resolve("test.db"));
    }

    @Test
    void conversationCrudAndMessageCascade() {
        Conversation c = store.addConversation("c1", "hello");
        assertThat(store.conversation("c1").title()).isEqualTo("hello");
        store.addMessage("c1", "user", "hi", null);

        store.renameConversation("c1", "renamed");
        assertThat(store.conversation("c1").title()).isEqualTo("renamed");

        store.deleteConversation("c1");
        assertThat(store.conversations()).isEmpty();
        assertThat(store.messages("c1")).isEmpty(); // messages cascade-deleted
    }

    @Test
    void recentMessagesReturnsNewestOldestFirst() {
        for (int i = 0; i < 5; i++) {
            store.addMessage("c1", "user", "q" + i, null);
        }
        assertThat(store.recentMessages("c1", 3))
                .extracting(Message::content)
                .containsExactly("q2", "q3", "q4");
    }
}
