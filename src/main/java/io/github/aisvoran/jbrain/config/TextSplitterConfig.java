package io.github.aisvoran.jbrain.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class TextSplitterConfig {

    private final KnowledgeBaseProperties properties;

    public TextSplitterConfig(KnowledgeBaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        // TokenTextSplitter constructor in Spring AI 2.0:
        // (int defaultChunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks, boolean keepSeparator, List<Character> separators)
        return new TokenTextSplitter(
                properties.chunkSize(),    // defaultChunkSize
                properties.chunkOverlap(), // minChunkSizeChars
                5,                         // minChunkLengthToEmbed
                10000,                     // maxNumChunks
                true,                      // keepSeparator
                List.of('\n', '.', '!', '?', ' ') // separators
        );
    }
}
