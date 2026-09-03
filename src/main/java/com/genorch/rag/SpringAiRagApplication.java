package com.genorch.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Boots the Spring context and, depending on
 * {@code app.rag.ingest-on-startup}, restores or builds the corpus index.
 *
 * <p>All wiring lives in {@code config.RagConfig}; the runtime behaviour is described by the
 * two pipelines in {@code docs/code-tour.md} (ingest / ask).
 */
@SpringBootApplication
public class SpringAiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiRagApplication.class, args);
    }
}
