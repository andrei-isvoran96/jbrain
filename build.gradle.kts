plugins {
	java
	id("org.springframework.boot") version "4.0.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.aisvoran"
version = "0.0.1-SNAPSHOT"
description = "JBrain - Personal Knowledge CLI"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
	maven { url = uri("https://repo.spring.io/milestone") }
	maven { url = uri("https://repo.spring.io/snapshot") }
}

extra["springAiVersion"] = "2.0.0-M1"

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

dependencies {
	// Spring Boot Web
	implementation("org.springframework.boot:spring-boot-starter-web")
	
	// Spring AI with Ollama (new naming pattern for 2.0)
	implementation("org.springframework.ai:spring-ai-starter-model-ollama")
	
	// Spring AI Vector Store (SimpleVectorStore is in this module)
	implementation("org.springframework.ai:spring-ai-vector-store")
	
	// For file watching
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	
	// Lombok for cleaner code (optional but useful)
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	
	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
