package io.github.aisvoran.jbrain.controller;

import io.github.aisvoran.jbrain.service.DocumentIngestionService;
import io.github.aisvoran.jbrain.service.DocumentIngestionService.IngestionStats;
import io.github.aisvoran.jbrain.service.FileWatcherService;
import io.github.aisvoran.jbrain.service.RagService;
import io.github.aisvoran.jbrain.service.RagService.RagResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;
    private final DocumentIngestionService ingestionService;
    private final FileWatcherService fileWatcherService;

    public RagController(
            RagService ragService,
            DocumentIngestionService ingestionService,
            FileWatcherService fileWatcherService
    ) {
        this.ragService = ragService;
        this.ingestionService = ingestionService;
        this.fileWatcherService = fileWatcherService;
    }

    @PostMapping("/ask")
    public ResponseEntity<RagResponse> ask(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Received question: {}", request.question());
        RagResponse response = ragService.ask(request.question());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ask")
    public ResponseEntity<RagResponse> askGet(@RequestParam("q") String question) {
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Received question (GET): {}", question);
        RagResponse response = ragService.ask(question);
        return ResponseEntity.ok(response);
    }

    /**
     * Streaming endpoint that returns tokens as Server-Sent Events.
     * Used by the CLI tool and web UI for real-time response display.
     * Each token is wrapped in JSON to preserve whitespace.
     * 
     * GET /api/ask/stream?q={question}
     */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamChunk>> askStream(@RequestParam("q") String question) {
        if (question == null || question.isBlank()) {
            return Flux.just(ServerSentEvent.<StreamChunk>builder()
                    .data(new StreamChunk("Error: Question cannot be empty"))
                    .build());
        }

        log.info("Received streaming question: {}", question);
        return ragService.askStream(question)
                .map(chunk -> ServerSentEvent.<StreamChunk>builder()
                        .data(new StreamChunk(chunk))
                        .build());
    }

    public record StreamChunk(String content) {}

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam("q") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        List<Document> results = ragService.search(query, topK);
        
        List<SearchResult> searchResults = results.stream()
                .map(doc -> new SearchResult(
                        (String) doc.getMetadata().get("filename"),
                        (String) doc.getMetadata().get("source"),
                        doc.getText(),
                        doc.getMetadata()
                ))
                .toList();

        return ResponseEntity.ok(new SearchResponse(query, searchResults.size(), searchResults));
    }

    /**
     * Triggers a full re-ingestion of all documents.
     * 
     * POST /api/ingest
     * POST /api/ingest?force=true  (to force re-index all files)
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> triggerIngestion(
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        log.info("Manual ingestion triggered (force={})", force);
        int count = ingestionService.ingestAll(force);
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "documentsProcessed", count,
                "force", force
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> getStats() {
        IngestionStats stats = ingestionService.getStats();
        return ResponseEntity.ok(new StatsResponse(
                stats.processedFileCount(),
                stats.documentsPath(),
                fileWatcherService.isRunning()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "jbrain-knowledge-cli"
        ));
    }

    // Request/Response DTOs

    public record AskRequest(String question) {}

    public record SearchResponse(
            String query,
            int resultCount,
            List<SearchResult> results
    ) {}

    public record SearchResult(
            String filename,
            String path,
            String content,
            Map<String, Object> metadata
    ) {}

    public record StatsResponse(
            int indexedFileCount,
            String documentsPath,
            boolean fileWatcherActive
    ) {}
}
