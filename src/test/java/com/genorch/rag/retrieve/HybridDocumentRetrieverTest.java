package com.genorch.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the single version-whitelist point shared by the vector and keyword legs. */
class HybridDocumentRetrieverTest {

    @Test
    void sanitizeVersionAcceptsSafeTokens() {
        assertThat(HybridDocumentRetriever.sanitizeVersion("2.0.1")).isEqualTo("2.0.1");
        assertThat(HybridDocumentRetriever.sanitizeVersion("v1.1.8")).isEqualTo("v1.1.8");
        assertThat(HybridDocumentRetriever.sanitizeVersion("milvus_2")).isEqualTo("milvus_2");
    }

    @Test
    void sanitizeVersionRejectsBlankAndUnsafeValues() {
        assertThat(HybridDocumentRetriever.sanitizeVersion(null)).isNull();
        assertThat(HybridDocumentRetriever.sanitizeVersion("")).isNull();
        assertThat(HybridDocumentRetriever.sanitizeVersion("   ")).isNull();
        // A single quote or space would break/alter the vector filter expression.
        assertThat(HybridDocumentRetriever.sanitizeVersion("2.0.1' OR 1=1--")).isNull();
        assertThat(HybridDocumentRetriever.sanitizeVersion("2.0 1")).isNull();
    }
}
