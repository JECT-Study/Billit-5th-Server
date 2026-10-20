import io.swagger.v3.core.util.Json
import io.swagger.v3.parser.OpenAPIResolver
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.swagger.parser.v3:swagger-parser-v3:2.1.48")
    }
}

plugins {
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.0"

    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
}

group = "com.ject"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.webjars.npm:scalar__api-reference:1.68.0") {
        isTransitive = false
    }

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:3.9.0")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:3.9.0")
    implementation("com.linecorp.kotlin-jdsl:hibernate-support:3.9.0")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation(platform("software.amazon.awssdk:bom:2.56.0"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.assertj")
        exclude(group = "org.junit.jupiter")
    }
    testImplementation("io.kotest:kotest-runner-junit5:6.1.0")
    testImplementation("io.kotest:kotest-assertions-core:6.1.0")
    testImplementation("io.kotest:kotest-extensions-spring:6.1.0")
    testImplementation("org.instancio:instancio-core:6.0.0")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("kotest")
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val apiSpecification = layout.projectDirectory.file("src/main/resources/api-docs/openapi.yml")
val apiSpecificationFiles = fileTree("src/main/resources/api-docs") { include("**/*.yml", "**/*.yaml") }
val bundledApiSpecification = layout.buildDirectory.file("generated/api-docs/openapi.json")

val bundleOpenApi by tasks.registering {
    group = "documentation"
    description = "Resolves and validates the multi-file OpenAPI specification."
    inputs.files(apiSpecificationFiles)
    outputs.file(bundledApiSpecification)

    doLast {
        val result =
            OpenAPIV3Parser().readLocation(
                apiSpecification.asFile.absolutePath,
                null,
                ParseOptions().apply {
                    isResolve = true
                    isValidateExternalRefs = true
                    isValidateInternalRefs = true
                },
            )
        check(result.messages.isNullOrEmpty()) {
            "OpenAPI validation failed:\n${result.messages.joinToString("\n")}"
        }
        val specification = checkNotNull(result.openAPI) { "Could not read the OpenAPI specification." }
        // Resolve nested references after importing external components.
        OpenAPIResolver(specification, null, apiSpecification.asFile.absolutePath).resolve(result)
        check(result.messages.isNullOrEmpty()) {
            "OpenAPI reference resolution failed:\n${result.messages.joinToString("\n")}"
        }
        val bundled = Json.pretty(specification)
        val document = Json.mapper().readTree(bundled)
        val invalidReferences =
            document.findValues("\$ref").map { it.asText() }.filter {
                !it.startsWith("#/") || document.at(it.removePrefix("#")).isMissingNode
            }
        check(invalidReferences.isEmpty()) { "Unresolved OpenAPI references: $invalidReferences" }
        val output = bundledApiSpecification.get().asFile
        output.parentFile.mkdirs()
        output.writeText(bundled, Charsets.UTF_8)
    }
}

tasks.check {
    dependsOn(bundleOpenApi)
}

tasks.processResources {
    dependsOn(bundleOpenApi)
    filteringCharset = "UTF-8"
    inputs.file(bundledApiSpecification)
    filesMatching("api-docs/swagger-ui.html") {
        filter { line: String ->
            line.replace(
                "<!-- OPENAPI_SPEC -->",
                groovy.json.JsonOutput
                    .toJson(bundledApiSpecification.get().asFile.readText(Charsets.UTF_8))
                    .replace("<", "\\u003c"),
            )
        }
    }
}
