package com.genorch.rag.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API for the chat UI: conversations, messages and folders, backed by
 * {@link ConversationStore} (SQLite). Message content is generated through {@code /ask};
 * this controller only manages the conversation structure and reads messages back.
 */
@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationController(ConversationStore store) {
        this.store = store;
    }

    @GetMapping("/conversations")
    public List<Conversation> conversations() {
        return store.conversations();
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> conversation(@PathVariable String id) {
        Conversation c = store.conversation(id);
        if (c == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found: " + id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation", c);
        body.put("messages", store.messages(id).stream().map(this::toClientMessage).toList());
        return body;
    }

    @PostMapping("/conversations")
    public Conversation create(@RequestBody(required = false) Map<String, String> body) {
        String id = UUID.randomUUID().toString();
        String title = body == null ? "新对话" : body.getOrDefault("title", "新对话");
        return store.addConversation(id, title);
    }

    @PatchMapping("/conversations/{id}")
    public void patch(@PathVariable String id, @RequestBody Map<String, String> body) {
        if (body.containsKey("title")) {
            store.renameConversation(id, body.get("title"));
        }
        if (body.containsKey("folderId")) {
            store.moveConversation(id, body.get("folderId"));
        }
    }

    @DeleteMapping("/conversations/{id}")
    public void delete(@PathVariable String id) {
        store.deleteConversation(id);
    }

    @GetMapping("/folders")
    public List<Folder> folders() {
        return store.folders();
    }

    @PostMapping("/folders")
    public Folder createFolder(@RequestBody Map<String, String> body) {
        return store.addFolder(body == null || body.get("name") == null ? "新文件夹" : body.get("name"));
    }

    @DeleteMapping("/folders/{id}")
    public void deleteFolder(@PathVariable String id) {
        store.deleteFolder(id);
    }

    /** Exposes a message with its {@code sourcesJson} parsed into a structured list. */
    private Map<String, Object> toClientMessage(Message m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("role", m.role());
        map.put("content", m.content());
        map.put("sources", parseSources(m.sourcesJson()));
        map.put("createdAt", m.createdAt());
        return map;
    }

    private List<?> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception e) {
            return List.of();
        }
    }
}
