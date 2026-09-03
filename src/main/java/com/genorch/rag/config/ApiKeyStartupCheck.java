package com.genorch.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Surfaces a missing DashScope key at startup instead of at the first embedding/chat call.
 *
 * <p>Without this the application boots cleanly and only fails much later, which looks like
 * a pipeline bug rather than a missing environment variable.
 */
@Component
public class ApiKeyStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStartupCheck.class);

    private final String apiKey;

    public ApiKeyStartupCheck(@Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("""
                    DASHSCOPE_API_KEY is not set - embedding / chat / rerank calls will fail.
                    Set the DASHSCOPE_API_KEY environment variable before starting the app.
                    """);
        }
    }
}
