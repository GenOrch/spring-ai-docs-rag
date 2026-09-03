package com.genorch.rag.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.genorch.rag.eval.EvalReport;
import com.genorch.rag.eval.EvalService;
import com.genorch.rag.ingest.IngestionService;

/**
 * Operational endpoints for (re)building the index and running retrieval evaluation.
 */
@RestController
public class AdminController {

    private final IngestionService ingestionService;
    private final EvalService evalService;

    public AdminController(IngestionService ingestionService, EvalService evalService) {
        this.ingestionService = ingestionService;
        this.evalService = evalService;
    }

    @PostMapping("/admin/ingest")
    public Map<String, Object> ingest() throws IOException {
        int chunks = ingestionService.ingest();
        return Map.of("chunks", chunks);
    }

    @GetMapping("/admin/eval")
    public EvalReport eval() throws IOException {
        return evalService.evaluate();
    }
}
