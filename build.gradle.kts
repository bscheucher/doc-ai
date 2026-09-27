import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.learning"
version = "0.0.1-SNAPSHOT"
description = "doc-ai"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springAiVersion = "1.1.8"
val pdfboxVersion = "3.0.8"
val springdocVersion = "2.9.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")

    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

tasks.named<Test>("test") {
    // Deliberately small: the intake tests assert that a document cannot make the service
    // allocate an unbounded raster, and that only holds if the heap is not generous.
    maxHeapSize = "512m"

    useJUnitPlatform {
        // Real-model tests are tagged 'llm' and never run in CI.
        // Opt in with: ./gradlew test -PincludeLlmTests
        if (!project.hasProperty("includeLlmTests")) {
            excludeTags("llm")
        }
    }
}

// OCI image via Cloud Native Buildpacks (Paketo) - no Dockerfile, no extra dependencies.
// Needs a running Docker daemon.
//   ./gradlew bootBuildImage
//   ./gradlew bootBuildImage -PimageName=<registry>.azurecr.io/doc-ai:0.0.1 --publishImage
tasks.named<BootBuildImage>("bootBuildImage") {
    imageName = providers.gradleProperty("imageName")
        .orElse("docai/${project.name}:${project.version}")

    // Match the Java toolchain; the builder would otherwise pick its own default.
    environment = mapOf("BP_JVM_VERSION" to "21")

    // Inert unless the registry properties are supplied (e.g. in ~/.gradle/gradle.properties).
    docker {
        publishRegistry {
            url = providers.gradleProperty("registryUrl")
            username = providers.gradleProperty("registryUsername")
            password = providers.gradleProperty("registryPassword")
        }
    }
}

