package com.genorch.rag.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.genorch.rag.document.DocumentMeta;
import com.genorch.rag.eval.EvalReport;
import com.genorch.rag.eval.EvalService;
import com.genorch.rag.ingest.store.CorpusStore;
import com.genorch.rag.observability.audit.AuditStore;
import com.genorch.rag.service.RagService;

/**
 * MCP tools exposed by the RAG service. This is the extension point: an AI agent can
 * inspect the running service (logs, status, evaluation) and drive it (ask a question)
 * over the Model Context Protocol, in addition to the plain HTTP endpoints.
 *
 * <p>Each method is an {@code @McpTool}; the MCP server annotation-scanner auto-discovers
 * them (no {@code ToolCallbackProvider} bean, which would otherwise introduce a dependency
 * cycle through the ChatClient's tool-call resolver).
 */
@Component
public class RagMcpTools {

    private final RagService ragService;
    private final EvalService evalService;
    private final CorpusStore corpusStore;
    private final AuditStore auditStore;

    public RagMcpTools(RagService ragService, EvalService evalService, CorpusStore corpusStore,
            AuditStore auditStore) {
        this.ragService = ragService;
        this.evalService = evalService;
        this.corpusStore = corpusStore;
        this.auditStore = auditStore;
    }

    @McpTool(name = "rag_ask", description = "Ask a question against the RAG knowledge base (Spring AI docs). "
            + "Returns the answer with numbered citations and a source URL list.")
    public String ragAsk(
            @McpToolParam(description = "The question, in Chinese or English", required = true) String question,
            @McpToolParam(description = "Optional Spring AI version filter, e.g. 2.0.1", required = false) String version) {
        RagService.Answer answer = ragService.ask(question, version);
        String body = answer.stream().collectList()
                .map(tokens -> String.join("", tokens))
                .block(Duration.ofSeconds(120));
        StringBuilder sb = new StringBuilder(body == null ? "" : body);
        if (!answer.sources().isEmpty()) {
            sb.append("\n\nSources:\n");
            for (RagService.Source source : answer.sources()) {
                sb.append("- ").append(source.url());
                if (source.version() != null && !source.version().isBlank()) {
                    sb.append(" (v").append(source.version()).append(')');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @McpTool(name = "rag_eval", description = "Run the retrieval/rerank evaluation over the golden question set and return the hit rates.")
    public EvalReport ragEval() throws IOException {
        return evalService.evaluate();
    }

    @McpTool(name = "rag_status", description = "Return the service status: index ready, chunk count and corpus versions.")
    public Map<String, Object> ragStatus() throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        if (corpusStore.exists()) {
            List<org.springframework.ai.document.Document> chunks = corpusStore.readAll();
            status.put("indexReady", true);
            status.put("chunks", chunks.size());
            List<String> versions = chunks.stream()
                    .map(d -> String.valueOf(d.getMetadata().getOrDefault(DocumentMeta.VERSION, "")))
                    .filter(v -> !v.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
            status.put("versions", versions);
        }
        else {
            status.put("indexReady", false);
            status.put("chunks", 0);
        }
        return status;
    }

    @McpTool(name = "rag_logs", description = "Return recent request audits and pipeline operation logs, newest first.")
    public Map<String, Object> ragLogs(
            @McpToolParam(description = "Max entries per list, defaults to 20", required = false) Integer limit) {
        int n = limit == null || limit <= 0 ? 20 : limit;
        Map<String, Object> logs = new LinkedHashMap<>();
        logs.put("audits", auditStore.audits(n));
        logs.put("operations", auditStore.operations(n));
        return logs;
    }
}
