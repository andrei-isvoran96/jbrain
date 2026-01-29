package io.github.aisvoran.jbrain.service;

import io.github.aisvoran.jbrain.config.KnowledgeBaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a helpful assistant that answers questions based on the user's personal knowledge base.
            
            Use the following context from the user's documents to answer their question.
            If the answer cannot be found in the context, say so clearly and offer to help in other ways.
            Always cite the source documents when using information from them.
            
            Context from knowledge base:
            ---
            %s
            ---
            
            Guidelines:
            - Be concise and accurate
            - If you use information from the context, mention which document it came from
            - If the context doesn't contain relevant information, acknowledge this
            - Do not make up information that isn't in the context
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final OllamaChatModel ollamaChatModel;
    private final KnowledgeBaseProperties properties;
    private final String defaultModel;

    public RagService(
            VectorStore vectorStore,
            ChatModel chatModel,
            KnowledgeBaseProperties properties
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.ollamaChatModel = (OllamaChatModel) chatModel;
        this.properties = properties;
        // Store the default model name from configuration
        this.defaultModel = ollamaChatModel.getDefaultOptions().getModel();
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public RagResponse ask(String question) {
        log.info("Processing question: {}", question);

        // Perform similarity search
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(properties.similarityTopK())
                .build();

        List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);
        
        log.debug("Found {} relevant documents", relevantDocs.size());

        if (relevantDocs.isEmpty()) {
            return new RagResponse(
                    "I couldn't find any relevant information in your knowledge base to answer this question. " +
                    "Please make sure you have documents indexed, or try rephrasing your question.",
                    List.of(),
                    question
            );
        }

        // Build context from retrieved documents
        String context = buildContext(relevantDocs);
        
        // Create the system prompt with context
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, context);

        // Generate response using the LLM
        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        // Extract source information
        List<SourceDocument> sources = relevantDocs.stream()
                .map(doc -> new SourceDocument(
                        (String) doc.getMetadata().get("filename"),
                        (String) doc.getMetadata().get("source"),
                        doc.getText().substring(0, Math.min(200, doc.getText().length())) + "..."
                ))
                .distinct()
                .toList();

        log.info("Generated response using {} source documents", sources.size());
        
        return new RagResponse(response, sources, question);
    }

    private String buildContext(List<Document> documents) {
        return documents.stream()
                .map(doc -> {
                    String filename = (String) doc.getMetadata().getOrDefault("filename", "unknown");
                    Integer chunkIndex = (Integer) doc.getMetadata().get("chunkIndex");
                    String chunkInfo = chunkIndex != null ? " (chunk " + chunkIndex + ")" : "";
                    
                    return String.format("[Source: %s%s]\n%s", filename, chunkInfo, doc.getText());
                })
                .collect(Collectors.joining("\n\n"));
    }

    public List<Document> search(String query, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        
        return vectorStore.similaritySearch(searchRequest);
    }

    /**
     * Streaming version of ask() that returns tokens as they are generated.
     * Used for CLI and real-time UI responses.
     */
    public Flux<String> askStream(String question) {
        return askStream(question, null);
    }

    /**
     * Streaming version of ask() with optional model override.
     * @param question The question to ask
     * @param model Optional model name to use (e.g., "llama3.2", "mistral", "qwen2:7b")
     */
    public Flux<String> askStream(String question, String model) {
        String modelToUse = (model != null && !model.isBlank()) ? model : defaultModel;
        log.info("Processing streaming question with model '{}': {}", modelToUse, question);

        // Perform similarity search
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(properties.similarityTopK())
                .build();

        List<Document> relevantDocs = vectorStore.similaritySearch(searchRequest);
        
        log.debug("Found {} relevant documents for streaming", relevantDocs.size());

        if (relevantDocs.isEmpty()) {
            return Flux.just("I couldn't find any relevant information in your knowledge base to answer this question. " +
                    "Please make sure you have documents indexed, or try rephrasing your question.");
        }

        // Build context from retrieved documents
        String context = buildContext(relevantDocs);
        
        // Create the system prompt with context
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, context);

        // Build chat options with the specified model
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(modelToUse)
                .build();

        // Stream the response using the LLM with model override
        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .options(options)
                .stream()
                .content();
    }

    public record RagResponse(
            String answer,
            List<SourceDocument> sources,
            String originalQuestion
    ) {}

    public record SourceDocument(
            String filename,
            String path,
            String preview
    ) {}
}
