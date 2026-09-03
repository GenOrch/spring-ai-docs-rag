package com.genorch.rag.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.genorch.rag.config.RagProperties;
import com.genorch.rag.observability.TraceIds;
import com.genorch.rag.service.RagService;

/**
 * REST entry point for the RAG Q&amp;A. Streams the answer token-by-token as
 * Server-Sent Events (SSE), then emits a final {@code sources} event carrying the
 * citation URL list, so a client can render both the answer and its sources.
 */
@RestController
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final RagService ragService;
    private final long askTimeoutMs;

    public AskController(RagService ragService, RagProperties properties) {
        this.ragService = ragService;
        this.askTimeoutMs = properties.askTimeoutMs() > 0 ? properties.askTimeoutMs() : 120_000L;
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody(required = false) AskRequest request) {
        SseEmitter emitter = new SseEmitter(askTimeoutMs);
        if (request == null || request.question() == null || request.question().isBlank()) {
            log.warn("rejected /ask: question must not be blank");
            emitter.completeWithError(new IllegalArgumentException("question must not be blank"));
            return emitter;
        }
        long start = System.nanoTime();
        // Capture the traceId now — the stream completes on a Reactor thread where the MDC
        // (thread-local) is empty, so pass it explicitly to keep the two log lines correlatable.
        String traceId = TraceIds.traceId();
        RagService.Answer answer = ragService.ask(request.question(), request.version(), request.conversationId());
        answer.stream().subscribe(
                token -> send(emitter, token),
                error -> {
                    log.error("ask failed after {} ms (traceId {})", elapsedMs(start), traceId, error);
                    emitter.completeWithError(error);
                },
                () -> {
                    try {
                        emitter.send(SseEmitter.event().name("sources").data(answer.sources()));
                    }
                    catch (IOException e) {
                        log.debug("client gone before sources event", e);
                    }
                    // The eager "ask" summary line logs the pre-generation phase; this line
                    // closes the loop with the full request time (retrieval + generation + stream).
                    log.info("ask completed in {} ms (traceId {})", elapsedMs(start), traceId);
                    emitter.complete();
                });
        return emitter;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void send(SseEmitter emitter, String token) {
        try {
            emitter.send(token);
        }
        catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
