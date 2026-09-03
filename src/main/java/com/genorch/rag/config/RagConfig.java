package com.genorch.rag.config;

import java.io.IOException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.genorch.rag.observability.RagMetrics;
import com.genorch.rag.retrieve.HybridDocumentRetriever;
import com.genorch.rag.retrieve.LuceneKeywordIndex;
import com.genorch.rag.rerank.DashScopeRerankPostProcessor;

/**
 * Assembles the RAG building blocks out of Spring AI 2.0 abstractions:
 *
 * <pre>
 * HybridDocumentRetriever (vector + BM25 + RRF)   -- custom DocumentRetriever
 *   -> DashScopeRerankPostProcessor (gte-rerank-v2) -- custom DocumentPostProcessor
 *   -> RagService assembles a numbered context and streams a cited answer
 * </pre>
 *
 * <p>The vector store is PostgreSQL + pgvector (auto-configured by the pgvector starter).
 * The same retriever+rerank pair also plugs directly into Spring AI's
 * {@code RetrievalAugmentationAdvisor}.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    @Bean(destroyMethod = "close")
    LuceneKeywordIndex keywordIndex() throws IOException {
        return new LuceneKeywordIndex();
    }

    @Bean
    TokenTextSplitter tokenTextSplitter(RagProperties properties) {
        RagProperties.Chunk chunk = properties.chunk();
        return TokenTextSplitter.builder()
            .withChunkSize(chunk.defaultChunkSize())
            .withMinChunkSizeChars(chunk.minChunkSizeChars())
            .withMinChunkLengthToEmbed(chunk.minChunkLengthToEmbed())
            .withMaxNumChunks(chunk.maxNumChunks())
            .withKeepSeparator(true)
            .build();
    }

    @Bean
    HybridDocumentRetriever hybridDocumentRetriever(VectorStore vectorStore, LuceneKeywordIndex keywordIndex,
            RagProperties properties, RagMetrics metrics) {
        return new HybridDocumentRetriever(vectorStore, keywordIndex, properties.retrieve(), metrics);
    }

    @Bean
    DashScopeRerankPostProcessor rerankPostProcessor(RagProperties properties,
            @Value("${spring.ai.openai.api-key:}") String apiKey, RestClient.Builder restClientBuilder,
            RagMetrics metrics) {
        return new DashScopeRerankPostProcessor(properties.rerank(), apiKey, restClientBuilder, metrics);
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    TranslationQueryTransformer translationQueryTransformer(ChatModel chatModel) {
        // Translates the user query into the corpus language (English) before retrieval, so a
        // Chinese question can still match the English docs lexically (BM25) and semantically.
        // Bean name must not collide with the QueryTranslator component that wraps it.
        return TranslationQueryTransformer.builder()
            .chatClientBuilder(ChatClient.builder(chatModel))
            .targetLanguage("english")
            .build();
    }
}
