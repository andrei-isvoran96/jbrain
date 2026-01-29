package io.github.aisvoran.jbrain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "jbrain.knowledge")
public record KnowledgeBaseProperties(
        Path documentsPath,
        Path vectorStorePath,
        int chunkSize,
        int chunkOverlap,
        int similarityTopK,
        List<String> fileExtensions
) {
    public KnowledgeBaseProperties {
        // Default values
        if (chunkSize <= 0) chunkSize = 1000;
        if (chunkOverlap < 0) chunkOverlap = 200;
        if (similarityTopK <= 0) similarityTopK = 5;
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            fileExtensions = List.of(".md", ".txt", ".markdown");
        }
    }
}
