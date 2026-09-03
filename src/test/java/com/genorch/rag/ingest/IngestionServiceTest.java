package com.genorch.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import com.genorch.rag.config.RagProperties;
import com.genorch.rag.document.DocumentMeta;
import com.genorch.rag.ingest.store.CorpusStore;
import com.genorch.rag.retrieve.LuceneKeywordIndex;

/**
 * Guards the ingestion contract: chunk ids must be stable (so re-ingesting updates rows
 * instead of appending duplicates) and an existing corpus must be reused without re-embedding.
 */
@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    private static final String SOURCE_URL = "https://docs.example.com/reference/api/chatclient.html";

    @Mock
    private DocumentReader reader;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private LuceneKeywordIndex keywordIndex;

    @Mock
    private CorpusStore corpusStore;

    /** Every document handed to {@code vectorStore.add}, in order. */
    private final List<Document> addedDocs = new ArrayList<>();

    private IngestionService service;

    @BeforeEach
    void setUp() {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(80)
                .withMinChunkSizeChars(20)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .build();
        service = new IngestionService(List.of(reader), splitter, vectorStore, keywordIndex, corpusStore, properties());
        addedDocs.clear();
    }

    /**
     * Starts recording the documents handed to {@code vectorStore.add}. Installed per test
     * rather than in {@code setUp}: Mockito's strict stubs reject a stub that a test never
     * uses, and the "reuse existing corpus" test must never call {@code add} at all.
     */
    @SuppressWarnings("unchecked")
    private void captureAddedDocs() {
        doAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(0);
            addedDocs.addAll(batch);
            return null;
        }).when(vectorStore).add(anyList());
    }

    @Test
    void reIngestionReusesTheSameChunkIds() throws IOException {
        captureAddedDocs();
        when(reader.get()).thenReturn(List.of(page()));

        service.ingest();
        List<String> firstRun = addedDocs.stream().map(Document::getId).toList();

        addedDocs.clear();
        service.ingest();
        List<String> secondRun = addedDocs.stream().map(Document::getId).toList();

        assertThat(firstRun).isNotEmpty();
        assertThat(secondRun)
                .as("ids are position-stable, so re-ingesting must yield the very same ids")
                .containsExactlyElementsOf(firstRun);
    }

    @Test
    void chunkIdsAreDistinctAndCarryTheirIndex() throws IOException {
        captureAddedDocs();
        when(reader.get()).thenReturn(List.of(page()));

        service.ingest();

        assertThat(addedDocs).isNotEmpty();
        assertThat(addedDocs).extracting(Document::getId).doesNotHaveDuplicates();
        assertThat(addedDocs.get(0).getMetadata())
                .containsEntry(DocumentMeta.CHUNK_INDEX, 0)
                .containsEntry(DocumentMeta.SOURCE_URL, SOURCE_URL);
    }

    @Test
    void loadOrIngestReusesExistingCorpusWithoutReEmbedding() throws IOException {
        Document existing = new Document("cached chunk",
                Map.of(DocumentMeta.SOURCE_URL, SOURCE_URL, DocumentMeta.CHUNK_INDEX, 0));
        when(corpusStore.exists()).thenReturn(true);
        when(corpusStore.readAll()).thenReturn(List.of(existing));

        int chunks = service.loadOrIngest();

        assertThat(chunks).isEqualTo(1);
        verify(vectorStore, never()).add(anyList());
        verify(keywordIndex).addAll(List.of(existing));
    }

    @Test
    void loadOrIngestIngestsWhenCorpusIsMissing() throws IOException {
        captureAddedDocs();
        when(corpusStore.exists()).thenReturn(false);
        when(reader.get()).thenReturn(List.of(page()));

        int chunks = service.loadOrIngest();

        assertThat(chunks).isPositive();
        assertThat(addedDocs).isNotEmpty();
    }

    @Test
    void unchangedContentIsSkippedOnReingestion() throws IOException {
        captureAddedDocs();
        when(reader.get()).thenReturn(List.of(page()));
        when(corpusStore.readAll()).thenReturn(List.of()); // empty on first run

        service.ingest();
        List<Document> firstRun = List.copyOf(addedDocs);

        // The store now returns the first-run chunks (with their content hashes).
        when(corpusStore.readAll()).thenReturn(firstRun);
        addedDocs.clear();

        service.ingest();

        assertThat(addedDocs).as("unchanged content must not be re-embedded").isEmpty();
    }

    @Test
    void changedContentIsReEmbedded() throws IOException {
        captureAddedDocs();
        when(reader.get()).thenReturn(List.of(page()));
        when(corpusStore.readAll()).thenReturn(List.of());

        service.ingest();
        List<Document> firstRun = List.copyOf(addedDocs);
        when(corpusStore.readAll()).thenReturn(firstRun);
        addedDocs.clear();

        // Same source URL, different text -> same position-based id, different content hash.
        when(reader.get()).thenReturn(List.of(page("totally different content about TokenTextSplitter")));
        service.ingest();

        assertThat(addedDocs).as("changed content must be re-embedded under its stable id").isNotEmpty();
    }

    private static Document page() {
        String text = """
                ChatClient offers a fluent API for interacting with an LLM. The builder lets you
                configure a default system prompt, advisors, and chat options. Advisors intercept
                the request before it reaches the model and can augment the prompt with retrieved
                documents. RetrievalAugmentationAdvisor is the out-of-the-box implementation of a
                retrieval augmented generation flow, and it supports query transformers as well as
                document post processors that rerank or compress the retrieved context.
                """;
        return page(text);
    }

    private static Document page(String text) {
        return new Document(text, Map.of(DocumentMeta.SOURCE_URL, SOURCE_URL, DocumentMeta.TITLE, "Chat Client API"));
    }

    private static RagProperties properties() {
        return new RagProperties(
                false,
                "./target/test-data",
                10,
                "vector_store",
                "system prompt",
                "",
                120_000L,
                new RagProperties.Chunk(80, 20, 5, 100),
                new RagProperties.Retrieve(20, 20, 20, 60),
                new RagProperties.Rerank(true, "http://rerank", "gte-rerank-v2", 5),
                new RagProperties.Mcp(500, 1000, false, "target/test-audit.jsonl"));
    }
}
