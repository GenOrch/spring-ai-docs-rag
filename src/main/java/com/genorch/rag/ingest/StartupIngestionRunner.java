package com.genorch.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.genorch.rag.config.RagProperties;

/**
 * Restores/ builds the index once at startup when {@code app.rag.ingest-on-startup} is
 * true: it loads a persisted index if one exists (no re-embedding), otherwise runs a
 * fresh ingest. Failures are logged, not fatal, so the app still boots (e.g. without an
 * API key) and can be re-ingested via the admin endpoint.
 */
@Component
public class StartupIngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestionRunner.class);

    private final IngestionService ingestionService;
    private final RagProperties properties;

    public StartupIngestionRunner(IngestionService ingestionService, RagProperties properties) {
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.ingestOnStartup()) {
            log.info("ingest-on-startup disabled; call POST /admin/ingest to build the index");
            return;
        }
        try {
            int chunks = ingestionService.loadOrIngest();
            log.info("startup index ready: {} chunks available", chunks);
        }
        catch (Exception e) {
            log.error("startup index build/restore failed (app continues without an index): {}", e.getMessage(), e);
        }
    }
}
