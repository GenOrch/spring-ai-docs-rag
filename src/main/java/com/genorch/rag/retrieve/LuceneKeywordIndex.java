package com.genorch.rag.retrieve;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.ai.document.Document;

import com.genorch.rag.document.DocumentMeta;

/**
 * Embedded BM25 keyword index (Apache Lucene) over the same chunks that go into the
 * vector store. This is the "keyword leg" of hybrid retrieval, giving the pipeline a
 * second, complementary recall signal without any external infrastructure.
 *
 * <p>Implementation note: the query is tokenized with the same analyzer and combined as
 * a {@code BooleanQuery} of SHOULD {@code TermQuery} clauses, so no queryparser module
 * is required.
 */
public class LuceneKeywordIndex implements AutoCloseable {

    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_VERSION = "version";

    private final Analyzer analyzer = new EnglishAnalyzer();
    private final Directory directory = new ByteBuffersDirectory();
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final Map<String, Document> documentsById = new ConcurrentHashMap<>();

    public LuceneKeywordIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        this.writer = new IndexWriter(directory, config);
        // Caches the IndexReader across searches; only reopened on commit (see addAll).
        this.searcherManager = new SearcherManager(writer, null);
    }

    /** Indexes the given documents. Replaces any previous entry with the same id. */
    public synchronized void addAll(List<Document> documents) throws IOException {
        for (Document document : documents) {
            org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();
            luceneDoc.add(new StringField(FIELD_ID, document.getId(), Field.Store.YES));
            luceneDoc.add(new TextField(FIELD_CONTENT, document.getText(), Field.Store.NO));
            luceneDoc.add(new StringField(FIELD_VERSION,
                    String.valueOf(document.getMetadata().getOrDefault(DocumentMeta.VERSION, "")),
                    Field.Store.NO));
            writer.updateDocument(new Term(FIELD_ID, document.getId()), luceneDoc);
            documentsById.put(document.getId(), document);
        }
        writer.commit();
        searcherManager.maybeRefresh();
    }

    /** Returns documents ranked by BM25 score, highest first, limited to {@code topK}. */
    public List<Document> search(String queryText, int topK) throws IOException {
        return search(queryText, topK, null);
    }

    /** Like {@link #search(String, int)}, with an optional exact version filter (MUST clause). */
    public List<Document> search(String queryText, int topK, String version) throws IOException {
        List<String> tokens = tokenize(queryText);
        if (tokens.isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String token : tokens) {
            builder.add(new TermQuery(new Term(FIELD_CONTENT, token)), BooleanClause.Occur.SHOULD);
        }
        if (version != null && !version.isBlank()) {
            builder.add(new TermQuery(new Term(FIELD_VERSION, version)), BooleanClause.Occur.MUST);
        }
        return searchInternal(builder.build(), topK);
    }

    private List<Document> searchInternal(org.apache.lucene.search.Query query, int topK) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs topDocs = searcher.search(query, topK);
            List<Document> results = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                org.apache.lucene.document.Document luceneDoc = searcher.storedFields().document(scoreDoc.doc);
                Document document = documentsById.get(luceneDoc.get(FIELD_ID));
                if (document != null) {
                    results.add(document);
                }
            }
            return results;
        }
        finally {
            searcherManager.release(searcher);
        }
    }

    private List<String> tokenize(String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(FIELD_CONTENT, text)) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(attr.toString());
            }
            stream.end();
        }
        return tokens;
    }

    @Override
    public void close() throws IOException {
        searcherManager.close();
        writer.close();
        directory.close();
    }
}
