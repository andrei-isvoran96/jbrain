# Build stage
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src src

# Build the application
RUN ./gradlew bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre

WORKDIR /app

# Create non-root user for security
RUN groupadd -r jbrain && useradd -r -g jbrain jbrain

# Create directories for data persistence
RUN mkdir -p /data/knowledge /data/vectorstore && \
    chown -R jbrain:jbrain /data

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Set ownership
RUN chown jbrain:jbrain app.jar

USER jbrain

# Environment variables (can be overridden)
ENV JBRAIN_KNOWLEDGE_DOCUMENTS_PATH=/data/knowledge
ENV JBRAIN_KNOWLEDGE_VECTOR_STORE_PATH=/data/vectorstore/vector-store.json
ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434

# Expose the application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
