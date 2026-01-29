package io.github.aisvoran.jbrain;

import io.github.aisvoran.jbrain.config.KnowledgeBaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
public class JbrainApplication {

	public static void main(String[] args) {
		SpringApplication.run(JbrainApplication.class, args);
	}

}
