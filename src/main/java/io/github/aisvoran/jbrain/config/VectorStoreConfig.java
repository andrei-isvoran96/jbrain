package io.github.aisvoran.jbrain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    private final KnowledgeBaseProperties properties;

    public VectorStoreConfig(KnowledgeBaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) throws IOException {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        
        Path vectorStorePath = properties.vectorStorePath();
        File vectorStoreFile = vectorStorePath.toFile();
        
        // Ensure parent directories exist
        Path parentDir = vectorStorePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
            log.info("Created vector store directory: {}", parentDir);
        }
        
        // Load existing vector store if it exists
        if (vectorStoreFile.exists()) {
            vectorStore.load(vectorStoreFile);
            log.info("Loaded existing vector store from: {}", vectorStorePath);
        } else {
            log.info("No existing vector store found. Starting fresh at: {}", vectorStorePath);
        }
        
        return vectorStore;
    }

    @Bean
    public VectorStorePersistence vectorStorePersistence(VectorStore vectorStore) {
        return new VectorStorePersistence((SimpleVectorStore) vectorStore, properties.vectorStorePath());
    }

    public static class VectorStorePersistence {
        
        private static final Logger log = LoggerFactory.getLogger(VectorStorePersistence.class);
        
        private final SimpleVectorStore vectorStore;
        private final Path persistencePath;

        public VectorStorePersistence(SimpleVectorStore vectorStore, Path persistencePath) {
            this.vectorStore = vectorStore;
            this.persistencePath = persistencePath;
        }

        public void save() {
            vectorStore.save(persistencePath.toFile());
            log.debug("Vector store saved to: {}", persistencePath);
        }
    }
}
