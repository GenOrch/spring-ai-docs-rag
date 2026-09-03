package com.genorch.rag.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genorch.rag.config.RagProperties;
import com.genorch.rag.conversation.ConversationStore;
import com.genorch.rag.conversation.Message;
import com.genorch.rag.document.DocumentMeta;
import com.genorch.rag.retrieve.HybridDocumentRetriever;
import com.genorch.rag.rerank.DashScopeRerankPostProcessor;

import reactor.core.publisher.Flux;

/**
 * Explicit RAG pipeline: hybrid retrieve -> rerank -> build numbered context -> stream
 * a citation-grounded answer. Retrieval is done eagerly (synchronously) so the source
 * list is available to return to the caller, while generation is streamed.
 *
 * <p>Multi-turn: when the caller supplies a {@code conversationId}, the most recent exchanges
 * are read back from {@link ConversationStore} (SQLite) and injected into the prompt as
 * conversation history, then the finished exchange is written back. Each {@link Source} also
 * carries its Spring AI {@code version}, so citations can show which doc version a chunk came
 * from.
 *
 * <p>Note: the same {@link HybridDocumentRetriever} + {@link DashScopeRerankPostProcessor}
 * pair also plugs into Spring AI's {@code RetrievalAugmentationAdvisor}; here we assemble
 * the prompt explicitly to keep full control over the numbered-source format returned to
 * the client.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** How many recent messages to feed back as multi-turn context (5 turns). */
    private static final int MAX_HISTORY_MESSAGES = 10;

    private final HybridDocumentRetriever retriever;
    private final DashScopeRerankPostProcessor rerank;
    private final QueryTranslator queryTranslator;
    private final ChatClient chatClient;
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String systemPrompt;

    public RagService(HybridDocumentRetriever retriever, DashScopeRerankPostProcessor rerank,
            QueryTranslator queryTranslator, ChatClient chatClient, ConversationStore conversationStore,
            RagProperties properties) {
        this.retriever = retriever;
        this.rerank = rerank;
        this.queryTranslator = queryTranslator;
        this.chatClient = chatClient;
        this.conversationStore = conversationStore;
        this.systemPrompt = properties.systemPrompt();
    }

    /** A citation source: the page URL, its title, and the Spring AI version it belongs to. */
    public record Source(String url, String title, String version) {
    }

    public record Answer(Flux<String> stream, List<Source> sources) {
    }

    /** Single-turn ask (no conversation memory). */
    public Answer ask(String question, String version) {
        return ask(question, version, null);
    }

    public Answer ask(String question, String version, String conversationId) {
        // Translate the query into the corpus language (English) for retrieval; generation
        // still uses the original question so the answer follows the user's language.
        Query query = Query.builder().text(question).build();
        Query retrievalQuery = queryTranslator.translate(query);
        // Version filter is pushed down into retrieval (vector filterExpression + BM25 version
        // field), so the retrieved list is already scoped to the requested version.
        List<Document> retrieved = retriever.retrieve(retrievalQuery, version);
        // Dedup by source URL BEFORE building the numbered context, so the [n] citation numbers
        // stay 1:1 with the returned source list.
        List<Document> documents = dedupBySourceUrl(rerank.process(retrievalQuery, retrieved));
        List<Source> sources = extractSources(documents);
        log.info("ask: question='{}' version={} conversation={} retrieved={} reranked={} sources={}",
                truncate(question, 120), version == null ? "-" : version,
                conversationId == null ? "-" : conversationId,
                retrieved.size(), documents.size(), sources.size());

        if (documents.isEmpty()) {
            String message = (version == null || version.isBlank())
                ? "No documents found — the index may be empty or the vector store may be "
                        + "unavailable (check the logs or rag_status)."
                : "No documents found for version '" + version + "'.";
            return new Answer(Flux.just(message), sources);
        }

        String context = buildContext(documents);
        String system = systemPrompt + "\n\nContext:\n" + context;
        String historyText = buildHistoryText(conversationId);
        if (!historyText.isBlank()) {
            system += "\n\nConversation history (earlier turns):\n" + historyText;
        }

        // Persist both turns atomically when the stream completes successfully: a failed
        // generation writes nothing, so there is never an orphaned user message.
        boolean persistent = conversationId != null && !conversationId.isBlank();

        // Accumulate the streamed tokens so the finished exchange can be stored as one turn.
        StringBuilder answer = new StringBuilder();
        Flux<String> stream = chatClient.prompt()
                .system(system)
                .user(question)
                .stream()
                .content()
                .map(token -> {
                    answer.append(token);
                    return token;
                })
                .doOnComplete(() -> {
                    if (persistent) {
                        conversationStore.addMessage(conversationId, "user", question, null);
                        conversationStore.addMessage(conversationId, "assistant",
                                answer.toString(), sourcesJson(sources));
                    }
                });
        return new Answer(stream, sources);
    }

    private String buildHistoryText(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "";
        }
        List<Message> history = conversationStore.recentMessages(conversationId, MAX_HISTORY_MESSAGES);
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            sb.append("user".equals(m.role()) ? "User: " : "Assistant: ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    private String sourcesJson(List<Source> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        }
        catch (Exception e) {
            return "[]";
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    /**
     * Keeps the first (highest-ranked) document per source URL. Aligning the context numbering
     * with the source list means citation {@code [n]} always points at source {@code [n]}.
     * Blank URLs fall back to the chunk id (unique), so they are never merged with each other.
     */
    static List<Document> dedupBySourceUrl(List<Document> documents) {
        Set<String> seen = new HashSet<>();
        List<Document> deduped = new ArrayList<>(documents.size());
        for (Document document : documents) {
            String url = String.valueOf(document.getMetadata().getOrDefault(DocumentMeta.SOURCE_URL, ""));
            String key = url.isBlank() ? document.getId() : url;
            if (seen.add(key)) {
                deduped.add(document);
            }
        }
        return deduped;
    }

    private List<Source> extractSources(List<Document> documents) {
        // One Source per document, in order — the list is already deduplicated by URL in ask(),
        // so index n here corresponds 1:1 to citation [n] in the generated answer.
        return documents.stream()
            .map(doc -> new Source(
                    String.valueOf(doc.getMetadata().getOrDefault(DocumentMeta.SOURCE_URL, "")),
                    String.valueOf(doc.getMetadata().getOrDefault(DocumentMeta.TITLE, "")),
                    String.valueOf(doc.getMetadata().getOrDefault(DocumentMeta.VERSION, ""))))
            .toList();
    }

    static String buildContext(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Document document : documents) {
            String title = String.valueOf(document.getMetadata().getOrDefault(DocumentMeta.TITLE, ""));
            String url = String.valueOf(document.getMetadata().getOrDefault(DocumentMeta.SOURCE_URL, ""));
            String version = String.valueOf(document.getMetadata().getOrDefault(DocumentMeta.VERSION, ""));
            sb.append('[').append(index++).append("] ");
            if (!title.isBlank()) {
                sb.append(title);
                if (!version.isBlank()) {
                    sb.append(" (v").append(version).append(')');
                }
                sb.append(" — ");
            }
            if (!url.isBlank()) {
                sb.append(url).append('\n');
            }
            sb.append(document.getText()).append("\n\n");
        }
        return sb.toString();
    }
}
