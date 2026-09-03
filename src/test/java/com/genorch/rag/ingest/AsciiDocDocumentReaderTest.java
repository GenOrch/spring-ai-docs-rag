package com.genorch.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AsciiDocDocumentReaderTest {

    @Test
    void stripsMarkupAndKeepsProseAndCode() {
        String adoc = """
                = Chat Client
                :icons: font

                == Usage

                The `ChatClient` offers a *fluent* API. See xref:chatclient.adoc[Chat Client].

                [source,java]
                ----
                var client = ChatClient.create(chatModel);
                ----

                // a comment
                """;

        String clean = AsciiDocDocumentReader.cleanAsciiDoc(adoc);

        assertThat(clean).contains("Usage");
        assertThat(clean).contains("ChatClient offers a fluent API");
        assertThat(clean).contains("var client = ChatClient.create(chatModel);");
        assertThat(clean).doesNotContain(":icons:");
        assertThat(clean).doesNotContain("[source,java]");
        assertThat(clean).doesNotContain("----");
        assertThat(clean).doesNotContain("// a comment");
        assertThat(clean).doesNotContain("xref:");
    }

    @Test
    void extractsDocumentTitle() {
        String adoc = """
                = Chat Client API
                == Overview
                """;
        assertThat(AsciiDocDocumentReader.extractTitle(adoc)).isEqualTo("Chat Client API");
    }

    @Test
    void fallsBackToFirstSectionWhenNoDocumentTitle() {
        String adoc = """
                == Overview
                Some prose.
                === Details
                """;
        assertThat(AsciiDocDocumentReader.extractTitle(adoc)).isEqualTo("Overview");
    }
}
