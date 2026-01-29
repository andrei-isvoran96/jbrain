package io.github.aisvoran.jbrain.service;

import io.github.aisvoran.jbrain.config.KnowledgeBaseProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;


@Service
public class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);

    private final KnowledgeBaseProperties properties;
    private final DocumentIngestionService ingestionService;

    private WatchService watchService;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FileWatcherService(
            KnowledgeBaseProperties properties,
            DocumentIngestionService ingestionService
    ) {
        this.properties = properties;
        this.ingestionService = ingestionService;
    }

    @PostConstruct
    public void init() {
        // Start watching for file changes first (non-blocking)
        startWatching();
        
        // Perform initial ingestion asynchronously to not block startup
        // This allows the app to start even if Ollama is temporarily unavailable
        Thread.ofVirtual().name("initial-ingestion").start(() -> {
            try {
                // Small delay to let the application fully start
                Thread.sleep(2000);
                log.info("Performing initial document ingestion...");
                int count = ingestionService.ingestAll();
                log.info("Initial ingestion complete. {} documents processed.", count);
            } catch (Exception e) {
                log.warn("Initial ingestion failed (Ollama may be unavailable). " +
                        "Documents will be indexed when you trigger manual ingestion or when files change. " +
                        "Error: {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        stopWatching();
    }

    public void startWatching() {
        if (running.get()) {
            log.warn("File watcher is already running");
            return;
        }

        Path documentsPath = properties.documentsPath();
        
        if (!Files.exists(documentsPath)) {
            log.warn("Documents path does not exist, cannot watch: {}", documentsPath);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            
            // Register the documents directory and all subdirectories
            registerDirectory(documentsPath);
            
            running.set(true);
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "file-watcher");
                t.setDaemon(true);
                return t;
            });
            
            executorService.submit(this::watchLoop);
            
            log.info("File watcher started for: {}", documentsPath);
            
        } catch (IOException e) {
            log.error("Failed to start file watcher", e);
        }
    }

    public void stopWatching() {
        if (!running.getAndSet(false)) {
            return;
        }

        log.info("Stopping file watcher...");
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Error closing watch service", e);
            }
        }
        
        log.info("File watcher stopped");
    }

    private void registerDirectory(Path dir) throws IOException {
        Files.walk(dir)
                .filter(Files::isDirectory)
                .forEach(path -> {
                    try {
                        path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
                        log.debug("Watching directory: {}", path);
                    } catch (IOException e) {
                        log.error("Failed to register directory: {}", path, e);
                    }
                });
    }

    private void watchLoop() {
        log.debug("File watcher loop started");
        
        while (running.get()) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                
                if (key == null) {
                    continue;
                }

                Path watchedDir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == OVERFLOW) {
                        log.warn("File watcher overflow event - some events may have been lost");
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = watchedDir.resolve(fileName);

                    if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                        // If a new directory is created, register it for watching
                        if (Files.isDirectory(fullPath)) {
                            try {
                                registerDirectory(fullPath);
                                log.info("New directory detected, now watching: {}", fullPath);
                            } catch (IOException e) {
                                log.error("Failed to register new directory: {}", fullPath, e);
                            }
                        } else {
                            // Small delay to ensure file write is complete
                            Thread.sleep(100);
                            ingestionService.onFileChanged(fullPath);
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.warn("Watch key is no longer valid for: {}", watchedDir);
                }

            } catch (InterruptedException e) {
                log.debug("File watcher interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                log.debug("Watch service closed");
                break;
            } catch (Exception e) {
                log.error("Error in file watcher loop", e);
            }
        }
        
        log.debug("File watcher loop ended");
    }

    public boolean isRunning() {
        return running.get();
    }
}
