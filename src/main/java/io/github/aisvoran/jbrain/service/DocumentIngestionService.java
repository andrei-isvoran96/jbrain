package io.github.aisvoran.jbrain.service;

import io.github.aisvoran.jbrain.config.KnowledgeBaseProperties;
import io.github.aisvoran.jbrain.config.VectorStoreConfig.VectorStorePersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final KnowledgeBaseProperties properties;
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final VectorStorePersistence vectorStorePersistence;

    // Track file modification times to avoid re-processing unchanged files
    private final Map<Path, Instant> processedFiles = new ConcurrentHashMap<>();

    public DocumentIngestionService(
            KnowledgeBaseProperties properties,
            VectorStore vectorStore,
            TokenTextSplitter textSplitter,
            VectorStorePersistence vectorStorePersistence
    ) {
        this.properties = properties;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.vectorStorePersistence = vectorStorePersistence;
    }

    /**
     * Performs a full scan of the documents directory and ingests all matching files.
     *
     * @return the number of documents processed
     */
    public int ingestAll() {
        return ingestAll(false);
    }

    /**
     * Performs a full scan of the documents directory and ingests all matching files.
     *
     * @param force if true, re-process all files regardless of whether they've been processed before
     * @return the number of documents processed
     */
    public int ingestAll(boolean force) {
        Path documentsPath = properties.documentsPath();
        
        if (!Files.exists(documentsPath)) {
            log.warn("Documents path does not exist: {}. Creating it.", documentsPath);
            try {
                Files.createDirectories(documentsPath);
            } catch (IOException e) {
                log.error("Failed to create documents directory: {}", documentsPath, e);
                return 0;
            }
        }

        if (force) {
            log.info("Force re-indexing enabled. Clearing processed files cache.");
            processedFiles.clear();
        }

        log.info("Starting full ingestion scan of: {}", documentsPath);
        
        List<Path> filesToProcess = findDocumentFiles(documentsPath);
        int processedCount = 0;
        
        for (Path file : filesToProcess) {
            if (ingestFile(file)) {
                processedCount++;
            }
        }
        
        if (processedCount > 0) {
            vectorStorePersistence.save();
        }
        
        log.info("Full ingestion complete. Processed {} files.", processedCount);
        return processedCount;
    }

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    /**
     * Ingests a single file into the vector store with retry logic.
     *
     * @param filePath the path to the file to ingest
     * @return true if the file was successfully processed
     */
    public boolean ingestFile(Path filePath) {
        if (!isValidDocumentFile(filePath)) {
            log.debug("Skipping non-document file: {}", filePath);
            return false;
        }

        try {
            Instant lastModified = Files.getLastModifiedTime(filePath).toInstant();
            Instant previouslyProcessed = processedFiles.get(filePath);
            
            if (previouslyProcessed != null && !lastModified.isAfter(previouslyProcessed)) {
                log.debug("File unchanged, skipping: {}", filePath);
                return false;
            }

            log.info("Ingesting file: {}", filePath);
            
            String content = Files.readString(filePath);
            String fileName = filePath.getFileName().toString();
            
            // Create document with metadata
            Document document = new Document(content, Map.of(
                    "source", filePath.toString(),
                    "filename", fileName,
                    "type", getFileType(fileName),
                    "lastModified", lastModified.toString()
            ));

            // Split into chunks
            List<Document> chunks = textSplitter.apply(List.of(document));
            
            // Add chunk index metadata
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("chunkIndex", i);
                chunk.getMetadata().put("totalChunks", chunks.size());
            }

            // Add to vector store with retry logic for transient Ollama errors
            boolean success = addToVectorStoreWithRetry(chunks, filePath);
            
            if (success) {
                // Track that we've processed this file
                processedFiles.put(filePath, lastModified);
                log.info("Successfully ingested {} chunks from: {}", chunks.size(), filePath);
            }
            
            return success;
            
        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Adds documents to the vector store with retry logic for transient errors.
     */
    private boolean addToVectorStoreWithRetry(List<Document> chunks, Path filePath) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                vectorStore.add(chunks);
                return true;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isTransient = errorMsg != null && 
                        (errorMsg.contains("EOF") || errorMsg.contains("500") || errorMsg.contains("timeout"));
                
                if (isTransient && attempt < MAX_RETRIES) {
                    log.warn("Attempt {}/{} failed for file: {}. Retrying in {}ms... Error: {}", 
                            attempt, MAX_RETRIES, filePath.getFileName(), RETRY_DELAY_MS, errorMsg);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Retry interrupted for file: {}", filePath);
                        return false;
                    }
                } else {
                    log.error("Failed to embed file after {} attempts (Ollama may be unavailable): {}. Error: {}", 
                            attempt, filePath, errorMsg);
                    return false;
                }
            }
        }
        return false;
    }

    public void onFileChanged(Path filePath) {
        log.debug("File change detected: {}", filePath);
        if (ingestFile(filePath)) {
            vectorStorePersistence.save();
        }
    }

    private List<Path> findDocumentFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                .filter(this::isValidDocumentFile)
                .forEach(files::add);
        } catch (IOException e) {
            log.error("Error scanning directory: {}", directory, e);
        }
        
        return files;
    }

    private boolean isValidDocumentFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        
        String fileName = path.getFileName().toString().toLowerCase();
        return properties.fileExtensions().stream()
                .anyMatch(ext -> fileName.endsWith(ext.toLowerCase()));
    }

    private String getFileType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            return "markdown";
        } else if (lowerName.endsWith(".txt")) {
            return "text";
        }
        return "unknown";
    }

    public IngestionStats getStats() {
        return new IngestionStats(
                processedFiles.size(),
                properties.documentsPath().toString()
        );
    }

    public record IngestionStats(int processedFileCount, String documentsPath) {}
}
