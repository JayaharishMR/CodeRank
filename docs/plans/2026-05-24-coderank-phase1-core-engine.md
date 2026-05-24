# CodeRank Phase 1: Core Execution Engine — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a working code execution API that accepts Java code via REST, executes it in a Docker sandbox, and streams real-time status via WebSocket. Auth via Keycloak, queue via RabbitMQ, storage in PostgreSQL.

**Architecture:** Monolith Spring Boot app (designed for future split). REST API receives submissions → enqueues to RabbitMQ → worker consumer picks up → executes in Docker container (docker-java SDK) → pushes status via WebSocket (STOMP) → saves results to PostgreSQL for registered users. Keycloak handles auth (JWT validation). Extension point system (SPI) built in from the start.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Security (OAuth2 Resource Server), Spring WebSocket (STOMP), docker-java SDK, RabbitMQ (spring-amqp), PostgreSQL (Spring Data JPA), Keycloak, Bucket4j + Redis (rate limiting), Docker Compose, JUnit 5 + Testcontainers

---

## Project Structure

```
coderank/
├── docker-compose.yml
├── docker/
│   └── sandbox/
│       └── Dockerfile              # Java sandbox image
├── src/main/java/com/coderank/
│   ├── CodeRankApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   ├── RabbitMQConfig.java
│   │   ├── WebSocketConfig.java
│   │   ├── DockerConfig.java
│   │   └── RateLimitConfig.java
│   ├── controller/
│   │   ├── SubmissionController.java
│   │   ├── HealthController.java
│   │   └── LanguageController.java
│   ├── dto/
│   │   ├── SubmissionRequest.java
│   │   ├── SubmissionResponse.java
│   │   ├── ExecutionStatusMessage.java
│   │   └── LanguageInfo.java
│   ├── model/
│   │   ├── Submission.java
│   │   ├── ExecutionResult.java
│   │   └── enums/
│   │       ├── SubmissionStatus.java
│   │       ├── Language.java
│   │       └── Verdict.java
│   ├── repository/
│   │   └── SubmissionRepository.java
│   ├── service/
│   │   ├── SubmissionService.java
│   │   ├── ExecutionService.java
│   │   └── WebSocketNotificationService.java
│   ├── worker/
│   │   ├── ExecutionWorker.java
│   │   └── DockerSandboxManager.java
│   └── extension/
│       ├── SubmissionEventListener.java
│       └── SubmissionEventPublisher.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       └── V1__init_schema.sql
├── src/test/java/com/coderank/
│   ├── controller/
│   │   └── SubmissionControllerTest.java
│   ├── service/
│   │   ├── SubmissionServiceTest.java
│   │   └── ExecutionServiceTest.java
│   ├── worker/
│   │   ├── ExecutionWorkerTest.java
│   │   └── DockerSandboxManagerTest.java
│   └── integration/
│       └── SubmissionIntegrationTest.java
└── pom.xml
```

---

## Task 1: Project Bootstrap + Docker Compose

**Files:**
- Create: `coderank/pom.xml`
- Create: `coderank/docker-compose.yml`
- Create: `coderank/src/main/java/com/coderank/CodeRankApplication.java`
- Create: `coderank/src/main/resources/application.yml`

**Step 1: Create Spring Boot project with pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativeTo/>
    </parent>

    <groupId>com.coderank</groupId>
    <artifactId>coderank</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>CodeRank</name>
    <description>Online Code Execution and Judging Platform</description>

    <properties>
        <java.version>21</java.version>
        <docker-java.version>3.3.6</docker-java.version>
        <bucket4j.version>8.10.1</bucket4j.version>
    </properties>

    <dependencies>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- WebSocket -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>

        <!-- Security + OAuth2 Resource Server (Keycloak JWT) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>

        <!-- Data -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- RabbitMQ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <!-- Docker Java SDK -->
        <dependency>
            <groupId>com.github.docker-java</groupId>
            <artifactId>docker-java-core</artifactId>
            <version>${docker-java.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.docker-java</groupId>
            <artifactId>docker-java-transport-httpclient5</artifactId>
            <version>${docker-java.version}</version>
        </dependency>

        <!-- Rate Limiting -->
        <dependency>
            <groupId>com.bucket4j</groupId>
            <artifactId>bucket4j_jdk17-core</artifactId>
            <version>${bucket4j.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**Step 2: Create main application class**

```java
// src/main/java/com/coderank/CodeRankApplication.java
package com.coderank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeRankApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeRankApplication.class, args);
    }
}
```

**Step 3: Create application.yml**

```yaml
# src/main/resources/application.yml
server:
  port: 8080

spring:
  application:
    name: coderank

  datasource:
    url: jdbc:postgresql://localhost:5432/coderank
    username: coderank
    password: coderank
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

  flyway:
    enabled: true
    locations: classpath:db/migration

  rabbitmq:
    host: localhost
    port: 5672
    username: coderank
    password: coderank

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9090/realms/coderank

  data:
    redis:
      host: localhost
      port: 6379

coderank:
  execution:
    timeout-seconds: 10
    compile-timeout-seconds: 30
    memory-limit-mb: 256
    cpu-count: 1
    max-stdout-bytes: 65536
    pid-limit: 50
    tmpfs-size-mb: 50
  docker:
    sandbox-image: coderank-sandbox-java
    pool-size: 10
  rate-limit:
    guest-requests-per-minute: 5
    user-requests-per-minute: 20
```

**Step 4: Create docker-compose.yml**

```yaml
# docker-compose.yml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: coderank
      POSTGRES_USER: coderank
      POSTGRES_PASSWORD: coderank
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: coderank
      RABBITMQ_DEFAULT_PASS: coderank
    ports:
      - "5672:5672"
      - "15672:15672"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  keycloak:
    image: quay.io/keycloak/keycloak:24.0
    command: start-dev
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/coderank
      KC_DB_USERNAME: coderank
      KC_DB_PASSWORD: coderank
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "9090:8080"
    depends_on:
      - postgres

volumes:
  postgres_data:
```

**Step 5: Verify project compiles**

Run: `cd coderank && mvn compile`
Expected: BUILD SUCCESS

**Step 6: Verify Docker Compose starts**

Run: `docker-compose up -d`
Expected: All 4 services running (postgres, rabbitmq, redis, keycloak)

**Step 7: Commit**

```bash
git add .
git commit -m "feat: bootstrap Spring Boot project with Docker Compose infra"
```

---

## Task 2: Database Schema + Flyway Migration

**Files:**
- Create: `coderank/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `coderank/src/main/java/com/coderank/model/enums/SubmissionStatus.java`
- Create: `coderank/src/main/java/com/coderank/model/enums/Language.java`
- Create: `coderank/src/main/java/com/coderank/model/enums/Verdict.java`
- Create: `coderank/src/main/java/com/coderank/model/Submission.java`

**Step 1: Create Flyway migration**

```sql
-- V1__init_schema.sql

CREATE TYPE submission_status AS ENUM (
    'QUEUED', 'COMPILING', 'RUNNING', 'COMPLETED', 'FAILED'
);

CREATE TYPE verdict AS ENUM (
    'ACCEPTED', 'WRONG_ANSWER', 'TIME_LIMIT_EXCEEDED',
    'MEMORY_LIMIT_EXCEEDED', 'RUNTIME_ERROR', 'COMPILATION_ERROR', 'PENDING'
);

CREATE TYPE language AS ENUM ('JAVA');

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id VARCHAR(255) NOT NULL UNIQUE,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    language language NOT NULL DEFAULT 'JAVA',
    source_code TEXT NOT NULL,
    stdin TEXT,
    status submission_status NOT NULL DEFAULT 'QUEUED',
    verdict verdict NOT NULL DEFAULT 'PENDING',
    stdout TEXT,
    stderr TEXT,
    execution_time_ms BIGINT,
    memory_used_kb BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_submissions_user_id ON submissions(user_id);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_submissions_created_at ON submissions(created_at DESC);
```

**Step 2: Create enum classes**

```java
// model/enums/SubmissionStatus.java
package com.coderank.model.enums;

public enum SubmissionStatus {
    QUEUED, COMPILING, RUNNING, COMPLETED, FAILED
}
```

```java
// model/enums/Language.java
package com.coderank.model.enums;

public enum Language {
    JAVA
}
```

```java
// model/enums/Verdict.java
package com.coderank.model.enums;

public enum Verdict {
    ACCEPTED, WRONG_ANSWER, TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED, RUNTIME_ERROR, COMPILATION_ERROR, PENDING
}
```

**Step 3: Create Submission entity**

```java
// model/Submission.java
package com.coderank.model;

import com.coderank.model.enums.Language;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Language language = Language.JAVA;

    @Column(name = "source_code", nullable = false, columnDefinition = "TEXT")
    private String sourceCode;

    @Column(columnDefinition = "TEXT")
    private String stdin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status = SubmissionStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict = Verdict.PENDING;

    @Column(columnDefinition = "TEXT")
    private String stdout;

    @Column(columnDefinition = "TEXT")
    private String stderr;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @Column(name = "memory_used_kb")
    private Long memoryUsedKb;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Language getLanguage() { return language; }
    public void setLanguage(Language language) { this.language = language; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public String getStdin() { return stdin; }
    public void setStdin(String stdin) { this.stdin = stdin; }
    public SubmissionStatus getStatus() { return status; }
    public void setStatus(SubmissionStatus status) { this.status = status; }
    public Verdict getVerdict() { return verdict; }
    public void setVerdict(Verdict verdict) { this.verdict = verdict; }
    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }
    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }
    public Long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public Long getMemoryUsedKb() { return memoryUsedKb; }
    public void setMemoryUsedKb(Long memoryUsedKb) { this.memoryUsedKb = memoryUsedKb; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

**Step 4: Create repository**

```java
// repository/SubmissionRepository.java
package com.coderank.repository;

import com.coderank.model.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {
    Page<Submission> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
```

**Step 5: Verify migration runs**

Run: `docker-compose up -d postgres && mvn spring-boot:run`
Expected: Flyway migration V1 applied, app starts without errors

**Step 6: Commit**

```bash
git add .
git commit -m "feat: add database schema, Flyway migration, and Submission entity"
```

---

## Task 3: Security Config (Keycloak + Guest Access)

**Files:**
- Create: `coderank/src/main/java/com/coderank/config/SecurityConfig.java`
- Create: `coderank/src/test/java/com/coderank/config/SecurityConfigTest.java`

**Step 1: Write failing test**

```java
// test/config/SecurityConfigTest.java
package com.coderank.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpoint_shouldBePublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    void languagesEndpoint_shouldBePublic() throws Exception {
        mockMvc.perform(get("/api/v1/languages"))
                .andExpect(status().isOk());
    }

    @Test
    void submissionsPost_shouldAllowGuest() throws Exception {
        mockMvc.perform(post("/api/v1/submissions")
                        .contentType("application/json")
                        .content("{\"sourceCode\":\"class Main{}\",\"language\":\"JAVA\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void submissionsGet_shouldRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/submissions"))
                .andExpect(status().isUnauthorized());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl coderank -Dtest=SecurityConfigTest`
Expected: FAIL — no security config, no controllers yet

**Step 3: Create SecurityConfig**

```java
// config/SecurityConfig.java
package com.coderank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/v1/health").permitAll()
                .requestMatchers("/api/v1/languages").permitAll()
                .requestMatchers("/ws/**").permitAll()

                // Submissions: POST allowed for guests, GET requires auth
                .requestMatchers(HttpMethod.POST, "/api/v1/submissions").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/submissions/**").authenticated()

                // Everything else requires auth
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }
}
```

**Step 4: Create stub controllers for tests to pass (minimal)**

```java
// controller/HealthController.java
package com.coderank.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
```

```java
// controller/LanguageController.java
package com.coderank.controller;

import com.coderank.model.enums.Language;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class LanguageController {

    @GetMapping("/languages")
    public List<Language> getLanguages() {
        return Arrays.asList(Language.values());
    }
}
```

**Step 5: Run tests**

Run: `mvn test -Dtest=SecurityConfigTest`
Expected: All 4 tests pass

**Step 6: Commit**

```bash
git add .
git commit -m "feat: add security config with Keycloak JWT + guest access"
```

---

## Task 4: DTOs + Submission Controller

**Files:**
- Create: `coderank/src/main/java/com/coderank/dto/SubmissionRequest.java`
- Create: `coderank/src/main/java/com/coderank/dto/SubmissionResponse.java`
- Create: `coderank/src/main/java/com/coderank/dto/ExecutionStatusMessage.java`
- Create: `coderank/src/main/java/com/coderank/controller/SubmissionController.java`
- Create: `coderank/src/test/java/com/coderank/controller/SubmissionControllerTest.java`

**Step 1: Create DTOs**

```java
// dto/SubmissionRequest.java
package com.coderank.dto;

import com.coderank.model.enums.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmissionRequest(
    @NotNull Language language,
    @NotBlank @Size(max = 51200) String sourceCode,
    String stdin
) {}
```

```java
// dto/SubmissionResponse.java
package com.coderank.dto;

import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import java.time.Instant;
import java.util.UUID;

public record SubmissionResponse(
    UUID id,
    SubmissionStatus status,
    Verdict verdict,
    String stdout,
    String stderr,
    Long executionTimeMs,
    Long memoryUsedKb,
    String errorMessage,
    String wsChannel,
    Instant createdAt
) {}
```

```java
// dto/ExecutionStatusMessage.java
package com.coderank.dto;

import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import java.time.Instant;

public record ExecutionStatusMessage(
    String submissionId,
    SubmissionStatus status,
    Verdict verdict,
    String stdout,
    String stderr,
    Long executionTimeMs,
    Long memoryUsedKb,
    String errorMessage,
    Instant timestamp
) {}
```

**Step 2: Write failing controller test**

```java
// test/controller/SubmissionControllerTest.java
package com.coderank.controller;

import com.coderank.model.Submission;
import com.coderank.model.enums.Language;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.service.SubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SubmissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SubmissionService submissionService;

    @Test
    void submitCode_shouldReturn202WithSubmissionId() throws Exception {
        Submission submission = new Submission();
        submission.setId(UUID.randomUUID());
        submission.setStatus(SubmissionStatus.QUEUED);
        submission.setLanguage(Language.JAVA);

        when(submissionService.submit(any(), any())).thenReturn(submission);

        mockMvc.perform(post("/api/v1/submissions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                            Map.of("language", "JAVA",
                                   "sourceCode", "public class Main { public static void main(String[] args) {} }"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.wsChannel").exists());
    }

    @Test
    void submitCode_withBlankSource_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/submissions")
                        .contentType("application/json")
                        .content("{\"language\":\"JAVA\",\"sourceCode\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitCode_withNoLanguage_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/submissions")
                        .contentType("application/json")
                        .content("{\"sourceCode\":\"class Main{}\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

**Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=SubmissionControllerTest`
Expected: FAIL — SubmissionService and SubmissionController don't exist yet

**Step 4: Create SubmissionController**

```java
// controller/SubmissionController.java
package com.coderank.controller;

import com.coderank.dto.SubmissionRequest;
import com.coderank.dto.SubmissionResponse;
import com.coderank.model.Submission;
import com.coderank.model.enums.Verdict;
import com.coderank.service.SubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionResponse submit(
            @Valid @RequestBody SubmissionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String userId = (jwt != null) ? jwt.getSubject() : null;
        Submission submission = submissionService.submit(request, userId);

        return new SubmissionResponse(
                submission.getId(),
                submission.getStatus(),
                submission.getVerdict(),
                submission.getStdout(),
                submission.getStderr(),
                submission.getExecutionTimeMs(),
                submission.getMemoryUsedKb(),
                submission.getErrorMessage(),
                "/topic/submissions/" + submission.getId(),
                submission.getCreatedAt()
        );
    }

    @GetMapping("/{id}")
    public SubmissionResponse getSubmission(@PathVariable UUID id) {
        Submission submission = submissionService.getById(id);
        return new SubmissionResponse(
                submission.getId(),
                submission.getStatus(),
                submission.getVerdict(),
                submission.getStdout(),
                submission.getStderr(),
                submission.getExecutionTimeMs(),
                submission.getMemoryUsedKb(),
                submission.getErrorMessage(),
                "/topic/submissions/" + submission.getId(),
                submission.getCreatedAt()
        );
    }
}
```

**Step 5: Create SubmissionService stub (enough for tests)**

```java
// service/SubmissionService.java
package com.coderank.service;

import com.coderank.dto.SubmissionRequest;
import com.coderank.model.Submission;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.repository.SubmissionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final RabbitTemplate rabbitTemplate;

    public SubmissionService(SubmissionRepository submissionRepository,
                             RabbitTemplate rabbitTemplate) {
        this.submissionRepository = submissionRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    public Submission submit(SubmissionRequest request, String keycloakUserId) {
        Submission submission = new Submission();
        submission.setLanguage(request.language());
        submission.setSourceCode(request.sourceCode());
        submission.setStdin(request.stdin());
        submission.setStatus(SubmissionStatus.QUEUED);

        // Only associate with user if authenticated
        if (keycloakUserId != null) {
            // TODO: resolve user from keycloak_id, set userId
        }

        submission = submissionRepository.save(submission);

        // Enqueue for execution
        rabbitTemplate.convertAndSend("coderank.submissions", submission.getId().toString());

        return submission;
    }

    public Submission getById(UUID id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Submission not found: " + id));
    }
}
```

**Step 6: Run tests**

Run: `mvn test -Dtest=SubmissionControllerTest`
Expected: All 3 tests pass

**Step 7: Commit**

```bash
git add .
git commit -m "feat: add submission controller, DTOs, and service"
```

---

## Task 5: RabbitMQ Config + WebSocket Config

**Files:**
- Create: `coderank/src/main/java/com/coderank/config/RabbitMQConfig.java`
- Create: `coderank/src/main/java/com/coderank/config/WebSocketConfig.java`
- Create: `coderank/src/main/java/com/coderank/service/WebSocketNotificationService.java`

**Step 1: Create RabbitMQ config**

```java
// config/RabbitMQConfig.java
package com.coderank.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String SUBMISSION_QUEUE = "coderank.submissions";

    @Bean
    public Queue submissionQueue() {
        return new Queue(SUBMISSION_QUEUE, true);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

**Step 2: Create WebSocket config**

```java
// config/WebSocketConfig.java
package com.coderank.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/execution")
                .setAllowedOrigins("*")
                .withSockJS();
    }
}
```

**Step 3: Create WebSocket notification service**

```java
// service/WebSocketNotificationService.java
package com.coderank.service;

import com.coderank.dto.ExecutionStatusMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebSocketNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketNotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendStatusUpdate(String submissionId, ExecutionStatusMessage message) {
        messagingTemplate.convertAndSend("/topic/submissions/" + submissionId, message);
    }
}
```

**Step 4: Verify app starts with all configs**

Run: `docker-compose up -d && mvn spring-boot:run`
Expected: App starts, connects to RabbitMQ, WebSocket endpoint available

**Step 5: Commit**

```bash
git add .
git commit -m "feat: add RabbitMQ queue config and WebSocket STOMP broker"
```

---

## Task 6: Docker Sandbox Image

**Files:**
- Create: `coderank/docker/sandbox/Dockerfile`
- Create: `coderank/docker/sandbox/entrypoint.sh`

**Step 1: Create sandbox Dockerfile**

```dockerfile
# docker/sandbox/Dockerfile
FROM eclipse-temurin:21-jdk-alpine

RUN adduser -D -s /bin/false coderank && \
    mkdir -p /workspace && \
    chown coderank:coderank /workspace

# Remove unnecessary tools
RUN apk --no-cache add bash && \
    rm -rf /usr/bin/wget /usr/bin/curl 2>/dev/null || true

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

USER coderank
WORKDIR /workspace

ENTRYPOINT ["/entrypoint.sh"]
```

**Step 2: Create entrypoint script**

```bash
#!/bin/bash
# docker/sandbox/entrypoint.sh

# Compile
javac -d /workspace/out /workspace/*.java 2>/workspace/compile_error.txt
COMPILE_EXIT=$?

if [ $COMPILE_EXIT -ne 0 ]; then
    echo "COMPILE_ERROR"
    cat /workspace/compile_error.txt
    exit 1
fi

# Find main class (class with main method)
MAIN_CLASS=$(grep -rl 'public static void main' /workspace/*.java | head -1 | xargs basename | sed 's/.java//')

if [ -z "$MAIN_CLASS" ]; then
    echo "COMPILE_ERROR"
    echo "No main method found"
    exit 1
fi

# Run
if [ -f /workspace/stdin.txt ]; then
    java -cp /workspace/out "$MAIN_CLASS" < /workspace/stdin.txt
else
    java -cp /workspace/out "$MAIN_CLASS"
fi
```

**Step 3: Build sandbox image**

Run: `cd coderank/docker/sandbox && docker build -t coderank-sandbox-java .`
Expected: Image builds successfully

**Step 4: Test sandbox manually**

Run:
```bash
echo 'public class Main { public static void main(String[] args) { System.out.println("Hello CodeRank"); } }' > /tmp/Main.java
docker run --rm -v /tmp/Main.java:/workspace/Main.java coderank-sandbox-java
```
Expected: Output `Hello CodeRank`

**Step 5: Commit**

```bash
git add .
git commit -m "feat: add Docker sandbox image for Java code execution"
```

---

## Task 7: Docker Sandbox Manager (docker-java SDK)

**Files:**
- Create: `coderank/src/main/java/com/coderank/config/DockerConfig.java`
- Create: `coderank/src/main/java/com/coderank/worker/DockerSandboxManager.java`
- Create: `coderank/src/test/java/com/coderank/worker/DockerSandboxManagerTest.java`

**Step 1: Create Docker client config**

```java
// config/DockerConfig.java
package com.coderank.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DockerConfig {

    @Bean
    public DockerClient dockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
```

**Step 2: Write failing test**

```java
// test/worker/DockerSandboxManagerTest.java
package com.coderank.worker;

import com.coderank.model.Submission;
import com.coderank.model.enums.Language;
import com.coderank.model.enums.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DockerSandboxManagerTest {

    @Autowired
    private DockerSandboxManager sandboxManager;

    @Test
    void execute_helloWorld_shouldReturnOutput() {
        Submission submission = new Submission();
        submission.setLanguage(Language.JAVA);
        submission.setSourceCode(
            "public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank\"); } }"
        );

        var result = sandboxManager.execute(submission);

        assertEquals(Verdict.ACCEPTED, result.verdict());
        assertEquals("Hello CodeRank\n", result.stdout());
        assertNull(result.errorMessage());
    }

    @Test
    void execute_withStdin_shouldReadInput() {
        Submission submission = new Submission();
        submission.setLanguage(Language.JAVA);
        submission.setSourceCode(
            "import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner sc = new Scanner(System.in); System.out.println(sc.nextInt() * 2); } }"
        );
        submission.setStdin("5");

        var result = sandboxManager.execute(submission);

        assertEquals(Verdict.ACCEPTED, result.verdict());
        assertEquals("10\n", result.stdout());
    }

    @Test
    void execute_compilationError_shouldReturnCE() {
        Submission submission = new Submission();
        submission.setLanguage(Language.JAVA);
        submission.setSourceCode("public class Main { invalid syntax }");

        var result = sandboxManager.execute(submission);

        assertEquals(Verdict.COMPILATION_ERROR, result.verdict());
        assertNotNull(result.stderr());
    }

    @Test
    void execute_runtimeError_shouldReturnRE() {
        Submission submission = new Submission();
        submission.setLanguage(Language.JAVA);
        submission.setSourceCode(
            "public class Main { public static void main(String[] args) { throw new RuntimeException(\"boom\"); } }"
        );

        var result = sandboxManager.execute(submission);

        assertEquals(Verdict.RUNTIME_ERROR, result.verdict());
    }

    @Test
    void execute_multipleClasses_shouldWork() {
        Submission submission = new Submission();
        submission.setLanguage(Language.JAVA);
        submission.setSourceCode(
            "class Helper { static String greet() { return \"Hello from Helper\"; } }\n" +
            "public class Main { public static void main(String[] args) { System.out.println(Helper.greet()); } }"
        );

        var result = sandboxManager.execute(submission);

        assertEquals(Verdict.ACCEPTED, result.verdict());
        assertEquals("Hello from Helper\n", result.stdout());
    }
}
```

**Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=DockerSandboxManagerTest`
Expected: FAIL — DockerSandboxManager doesn't exist

**Step 4: Create ExecutionResult record**

```java
// model/ExecutionResult.java
package com.coderank.model;

import com.coderank.model.enums.Verdict;

public record ExecutionResult(
    Verdict verdict,
    String stdout,
    String stderr,
    Long executionTimeMs,
    Long memoryUsedKb,
    String errorMessage
) {}
```

**Step 5: Create DockerSandboxManager**

```java
// worker/DockerSandboxManager.java
package com.coderank.worker;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;
import com.coderank.model.enums.Verdict;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

@Component
public class DockerSandboxManager {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxManager.class);

    private final DockerClient dockerClient;

    @Value("${coderank.docker.sandbox-image}")
    private String sandboxImage;

    @Value("${coderank.execution.timeout-seconds}")
    private int timeoutSeconds;

    @Value("${coderank.execution.memory-limit-mb}")
    private int memoryLimitMb;

    @Value("${coderank.execution.cpu-count}")
    private int cpuCount;

    @Value("${coderank.execution.pid-limit}")
    private int pidLimit;

    @Value("${coderank.execution.max-stdout-bytes}")
    private int maxStdoutBytes;

    public DockerSandboxManager(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public ExecutionResult execute(Submission submission) {
        String containerId = null;
        Path tempDir = null;
        long startTime = System.currentTimeMillis();

        try {
            // Write source code to temp dir
            tempDir = Files.createTempDirectory("coderank-");
            Path sourceFile = tempDir.resolve("Main.java");
            Files.writeString(sourceFile, submission.getSourceCode());

            if (submission.getStdin() != null) {
                Files.writeString(tempDir.resolve("stdin.txt"), submission.getStdin());
            }

            // Create container
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory((long) memoryLimitMb * 1024 * 1024)
                    .withCpuCount((long) cpuCount)
                    .withPidsLimit((long) pidLimit)
                    .withNetworkMode("none")
                    .withReadonlyRootfs(false)
                    .withCapDrop(Capability.ALL)
                    .withSecurityOpts(java.util.List.of("no-new-privileges"))
                    .withBinds(
                        new com.github.dockerjava.api.model.Bind(
                            tempDir.toAbsolutePath().toString(),
                            new com.github.dockerjava.api.model.Volume("/workspace")
                        )
                    );

            CreateContainerResponse container = dockerClient.createContainerCmd(sandboxImage)
                    .withHostConfig(hostConfig)
                    .exec();

            containerId = container.getId();

            // Start container
            dockerClient.startContainerCmd(containerId).exec();

            // Wait for completion with timeout
            int exitCode;
            try {
                exitCode = dockerClient.waitContainerCmd(containerId)
                        .exec(new WaitContainerResultCallback())
                        .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Timeout — kill container
                try { dockerClient.killContainerCmd(containerId).exec(); } catch (Exception ignored) {}
                long elapsed = System.currentTimeMillis() - startTime;
                return new ExecutionResult(Verdict.TIME_LIMIT_EXCEEDED, null, null, elapsed, null,
                        "Execution timed out after " + timeoutSeconds + " seconds");
            }

            // Collect stdout and stderr
            String stdout = collectLogs(containerId, true);
            String stderr = collectLogs(containerId, false);

            // Truncate stdout
            if (stdout != null && stdout.length() > maxStdoutBytes) {
                stdout = stdout.substring(0, maxStdoutBytes) + "\n[output truncated]";
            }

            long elapsed = System.currentTimeMillis() - startTime;

            // Determine verdict
            if (exitCode == 0) {
                return new ExecutionResult(Verdict.ACCEPTED, stdout, stderr, elapsed, null, null);
            } else if (stdout != null && stdout.startsWith("COMPILE_ERROR")) {
                String compileError = stdout.replaceFirst("COMPILE_ERROR\n?", "");
                return new ExecutionResult(Verdict.COMPILATION_ERROR, null, compileError, elapsed, null, null);
            } else {
                return new ExecutionResult(Verdict.RUNTIME_ERROR, stdout, stderr, elapsed, null,
                        "Process exited with code " + exitCode);
            }

        } catch (Exception e) {
            log.error("Execution failed for submission", e);
            long elapsed = System.currentTimeMillis() - startTime;
            return new ExecutionResult(Verdict.RUNTIME_ERROR, null, null, elapsed, null, e.getMessage());
        } finally {
            // Cleanup container
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception ignored) {}
            }
            // Cleanup temp dir
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            }
        }
    }

    private String collectLogs(String containerId, boolean stdout) {
        try {
            var callback = dockerClient.logContainerCmd(containerId)
                    .withStdOut(stdout)
                    .withStdErr(!stdout)
                    .withFollowStream(false)
                    .exec(new com.github.dockerjava.core.command.LogContainerResultCallback());
            callback.awaitCompletion(5, TimeUnit.SECONDS);
            return callback.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
```

**Step 6: Run tests**

Run: `mvn test -Dtest=DockerSandboxManagerTest`
Expected: All 5 tests pass (requires Docker running locally + sandbox image built)

**Step 7: Commit**

```bash
git add .
git commit -m "feat: add Docker sandbox manager with security hardening"
```

---

## Task 8: Execution Worker (RabbitMQ Consumer)

**Files:**
- Create: `coderank/src/main/java/com/coderank/worker/ExecutionWorker.java`
- Create: `coderank/src/test/java/com/coderank/worker/ExecutionWorkerTest.java`

**Step 1: Write failing test**

```java
// test/worker/ExecutionWorkerTest.java
package com.coderank.worker;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;
import com.coderank.model.enums.Language;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import com.coderank.repository.SubmissionRepository;
import com.coderank.service.WebSocketNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionWorkerTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private DockerSandboxManager sandboxManager;

    @Mock
    private WebSocketNotificationService notificationService;

    @InjectMocks
    private ExecutionWorker executionWorker;

    @Test
    void processSubmission_shouldExecuteAndUpdateStatus() {
        UUID submissionId = UUID.randomUUID();
        Submission submission = new Submission();
        submission.setId(submissionId);
        submission.setLanguage(Language.JAVA);
        submission.setSourceCode("public class Main { public static void main(String[] args) {} }");
        submission.setStatus(SubmissionStatus.QUEUED);

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(sandboxManager.execute(submission)).thenReturn(
            new ExecutionResult(Verdict.ACCEPTED, "output", null, 150L, null, null)
        );
        when(submissionRepository.save(any())).thenReturn(submission);

        executionWorker.processSubmission(submissionId.toString());

        verify(submissionRepository, atLeastOnce()).save(any());
        verify(notificationService, atLeastOnce()).sendStatusUpdate(eq(submissionId.toString()), any());
    }

    @Test
    void processSubmission_invalidId_shouldNotThrow() {
        when(submissionRepository.findById(any())).thenReturn(Optional.empty());

        executionWorker.processSubmission(UUID.randomUUID().toString());

        verify(sandboxManager, never()).execute(any());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ExecutionWorkerTest`
Expected: FAIL — ExecutionWorker doesn't exist

**Step 3: Create ExecutionWorker**

```java
// worker/ExecutionWorker.java
package com.coderank.worker;

import com.coderank.config.RabbitMQConfig;
import com.coderank.dto.ExecutionStatusMessage;
import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.repository.SubmissionRepository;
import com.coderank.service.WebSocketNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class ExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWorker.class);

    private final SubmissionRepository submissionRepository;
    private final DockerSandboxManager sandboxManager;
    private final WebSocketNotificationService notificationService;

    public ExecutionWorker(SubmissionRepository submissionRepository,
                           DockerSandboxManager sandboxManager,
                           WebSocketNotificationService notificationService) {
        this.submissionRepository = submissionRepository;
        this.sandboxManager = sandboxManager;
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = RabbitMQConfig.SUBMISSION_QUEUE)
    public void processSubmission(String submissionIdStr) {
        UUID submissionId = UUID.fromString(submissionIdStr);
        log.info("Processing submission: {}", submissionId);

        Submission submission = submissionRepository.findById(submissionId).orElse(null);
        if (submission == null) {
            log.warn("Submission not found: {}", submissionId);
            return;
        }

        try {
            // Update status: COMPILING
            updateStatus(submission, SubmissionStatus.COMPILING);

            // Update status: RUNNING
            updateStatus(submission, SubmissionStatus.RUNNING);

            // Execute in sandbox
            ExecutionResult result = sandboxManager.execute(submission);

            // Update final result
            submission.setStatus(SubmissionStatus.COMPLETED);
            submission.setVerdict(result.verdict());
            submission.setStdout(result.stdout());
            submission.setStderr(result.stderr());
            submission.setExecutionTimeMs(result.executionTimeMs());
            submission.setMemoryUsedKb(result.memoryUsedKb());
            submission.setErrorMessage(result.errorMessage());

            // Don't persist guest submissions
            if (submission.getUserId() != null) {
                submissionRepository.save(submission);
            }

            // Notify via WebSocket
            sendNotification(submission);

            log.info("Submission {} completed: {}", submissionId, result.verdict());

        } catch (Exception e) {
            log.error("Failed to process submission: {}", submissionId, e);
            submission.setStatus(SubmissionStatus.FAILED);
            submission.setErrorMessage(e.getMessage());
            submissionRepository.save(submission);
            sendNotification(submission);
        }
    }

    private void updateStatus(Submission submission, SubmissionStatus status) {
        submission.setStatus(status);
        submissionRepository.save(submission);
        sendNotification(submission);
    }

    private void sendNotification(Submission submission) {
        notificationService.sendStatusUpdate(
            submission.getId().toString(),
            new ExecutionStatusMessage(
                submission.getId().toString(),
                submission.getStatus(),
                submission.getVerdict(),
                submission.getStdout(),
                submission.getStderr(),
                submission.getExecutionTimeMs(),
                submission.getMemoryUsedKb(),
                submission.getErrorMessage(),
                Instant.now()
            )
        );
    }
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=ExecutionWorkerTest`
Expected: All 2 tests pass

**Step 5: Commit**

```bash
git add .
git commit -m "feat: add execution worker with RabbitMQ consumer and WebSocket notifications"
```

---

## Task 9: Rate Limiting

**Files:**
- Create: `coderank/src/main/java/com/coderank/config/RateLimitConfig.java`
- Create: `coderank/src/main/java/com/coderank/config/RateLimitFilter.java`

**Step 1: Create rate limit config**

```java
// config/RateLimitConfig.java
package com.coderank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "coderank.rate-limit")
public class RateLimitConfig {

    private int guestRequestsPerMinute = 5;
    private int userRequestsPerMinute = 20;

    public int getGuestRequestsPerMinute() { return guestRequestsPerMinute; }
    public void setGuestRequestsPerMinute(int v) { this.guestRequestsPerMinute = v; }
    public int getUserRequestsPerMinute() { return userRequestsPerMinute; }
    public void setUserRequestsPerMinute(int v) { this.userRequestsPerMinute = v; }
}
```

**Step 2: Create rate limit filter**

```java
// config/RateLimitFilter.java
package com.coderank.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig config;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitConfig config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v1/submissions") || !"POST".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(isAuthenticated()));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
        }
    }

    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "guest:" + request.getRemoteAddr();
    }

    private Bucket createBucket(boolean authenticated) {
        int rpm = authenticated ? config.getUserRequestsPerMinute() : config.getGuestRequestsPerMinute();
        return Bucket.builder()
                .addLimit(Bandwidth.simple(rpm, Duration.ofMinutes(1)))
                .build();
    }
}
```

**Step 3: Verify app starts with rate limiting**

Run: `mvn spring-boot:run`
Expected: No errors, rate limit filter active

**Step 4: Commit**

```bash
git add .
git commit -m "feat: add configurable rate limiting with Bucket4j"
```

---

## Task 10: Extension Point System (SPI)

**Files:**
- Create: `coderank/src/main/java/com/coderank/extension/SubmissionEventListener.java`
- Create: `coderank/src/main/java/com/coderank/extension/SubmissionEventPublisher.java`

**Step 1: Create extension interface**

```java
// extension/SubmissionEventListener.java
package com.coderank.extension;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;

public interface SubmissionEventListener {

    default void onSubmissionReceived(Submission submission) {}

    default void onSubmissionComplete(Submission submission, ExecutionResult result) {}

    default void onSubmissionFailed(Submission submission, String error) {}
}
```

**Step 2: Create event publisher**

```java
// extension/SubmissionEventPublisher.java
package com.coderank.extension;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SubmissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventPublisher.class);

    private final List<SubmissionEventListener> listeners;

    public SubmissionEventPublisher(List<SubmissionEventListener> listeners) {
        this.listeners = listeners;
    }

    public void publishReceived(Submission submission) {
        listeners.forEach(l -> {
            try { l.onSubmissionReceived(submission); }
            catch (Exception e) { log.error("Extension error on received", e); }
        });
    }

    public void publishComplete(Submission submission, ExecutionResult result) {
        listeners.forEach(l -> {
            try { l.onSubmissionComplete(submission, result); }
            catch (Exception e) { log.error("Extension error on complete", e); }
        });
    }

    public void publishFailed(Submission submission, String error) {
        listeners.forEach(l -> {
            try { l.onSubmissionFailed(submission, error); }
            catch (Exception e) { log.error("Extension error on failed", e); }
        });
    }
}
```

**Step 3: Wire publisher into ExecutionWorker**

Modify `ExecutionWorker.java` — add `SubmissionEventPublisher` to constructor and call `publishComplete`/`publishFailed` after execution.

**Step 4: Commit**

```bash
git add .
git commit -m "feat: add extension point system (SPI) for submission lifecycle events"
```

---

## Task 11: Integration Test (End-to-End)

**Files:**
- Create: `coderank/src/test/java/com/coderank/integration/SubmissionIntegrationTest.java`

**Step 1: Write integration test**

```java
// test/integration/SubmissionIntegrationTest.java
package com.coderank.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SubmissionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("coderank_test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        // Disable Keycloak JWT for integration tests
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submitCode_shouldAcceptAndEnqueue() throws Exception {
        var body = Map.of(
            "language", "JAVA",
            "sourceCode", "public class Main { public static void main(String[] args) { System.out.println(\"test\"); } }"
        );

        mockMvc.perform(post("/api/v1/submissions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.wsChannel").isNotEmpty());
    }
}
```

**Step 2: Run integration test**

Run: `mvn test -Dtest=SubmissionIntegrationTest`
Expected: PASS (requires Docker for Testcontainers)

**Step 3: Commit**

```bash
git add .
git commit -m "test: add end-to-end integration test with Testcontainers"
```

---

## Task 12: Final Verification + Cleanup

**Step 1: Run all tests**

Run: `mvn test`
Expected: All tests pass

**Step 2: Start full stack and test manually**

Run:
```bash
docker-compose up -d
cd coderank/docker/sandbox && docker build -t coderank-sandbox-java .
cd ../.. && mvn spring-boot:run
```

**Step 3: Test via curl**

```bash
# Health check
curl http://localhost:8080/api/v1/health

# Submit code
curl -X POST http://localhost:8080/api/v1/submissions \
  -H "Content-Type: application/json" \
  -d '{"language":"JAVA","sourceCode":"public class Main { public static void main(String[] args) { System.out.println(\"Hello CodeRank!\"); } }"}'

# Languages
curl http://localhost:8080/api/v1/languages
```

**Step 4: Verify WebSocket works**

Use a WebSocket client (wscat, Postman) to connect to `ws://localhost:8080/ws/execution` and subscribe to `/topic/submissions/{id}`.

**Step 5: Commit**

```bash
git add .
git commit -m "feat: CodeRank Phase 1 core execution engine complete"
```

---

## Summary

| Task | Component | Estimated Effort |
|------|-----------|-----------------|
| 1 | Project bootstrap + Docker Compose | Foundation |
| 2 | Database schema + Flyway + Entities | Data layer |
| 3 | Security config (Keycloak + Guest) | Auth |
| 4 | DTOs + Submission Controller | API layer |
| 5 | RabbitMQ + WebSocket config | Messaging |
| 6 | Docker sandbox image | Execution env |
| 7 | Docker Sandbox Manager | Core engine |
| 8 | Execution Worker (queue consumer) | Orchestration |
| 9 | Rate limiting | Security |
| 10 | Extension point system (SPI) | Extensibility |
| 11 | Integration test | Verification |
| 12 | Final verification + cleanup | Ship it |

**Dependencies:** Task 1 → 2 → 3 → 4 (sequential). Tasks 5, 6 can parallel after 1. Task 7 needs 6. Task 8 needs 5+7. Tasks 9, 10 independent. Task 11 needs all. Task 12 last.
