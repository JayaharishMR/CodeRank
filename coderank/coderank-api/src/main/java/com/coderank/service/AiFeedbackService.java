package com.coderank.service;

import com.coderank.config.AiConfig;
import com.coderank.dto.AiFeedbackMessage;
import com.coderank.model.AiFeedback;
import com.coderank.model.Submission;
import com.coderank.repository.AiFeedbackRepository;
import com.coderank.repository.SubmissionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls the OpenAI chat-completions API asynchronously to generate code review
 * feedback for a completed submission. Results are persisted to the database for
 * idempotency and pushed to the frontend via WebSocket so the user sees feedback
 * appear in real time without polling.
 *
 * Only loaded when {@code coderank.ai.enabled=true}; otherwise the bean does not
 * exist and the controller gracefully reports that AI is disabled.
 */
@Service
@ConditionalOnProperty(name = "coderank.ai.enabled", havingValue = "true")
public class AiFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AiFeedbackService.class);

    private final AiConfig aiConfig;
    private final AiFeedbackRepository aiFeedbackRepository;
    private final SubmissionRepository submissionRepository;
    private final WebSocketNotificationService notificationService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiFeedbackService(AiConfig aiConfig,
                             AiFeedbackRepository aiFeedbackRepository,
                             SubmissionRepository submissionRepository,
                             WebSocketNotificationService notificationService,
                             ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.aiFeedbackRepository = aiFeedbackRepository;
        this.submissionRepository = submissionRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;

        // Build a RestClient pre-configured with the OpenAI base URL and auth header
        // so every outbound call carries the API key automatically.
        this.restClient = RestClient.builder()
                .baseUrl(aiConfig.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + aiConfig.getApiKey())
                .build();
    }

    /**
     * Kicks off an async AI feedback request. If feedback already exists for this
     * submission (idempotency check), the cached result is re-sent via WebSocket
     * instead of hitting the API again.
     */
    @Async("aiTaskExecutor")
    public void requestFeedback(UUID submissionId) {
        // 1. Fetch submission, validate it exists
        Submission submission = submissionRepository.findByIdWithProblem(submissionId)
                .orElseThrow(() -> new RuntimeException("Submission not found: " + submissionId));

        // 2. Idempotency -- if feedback already exists, re-send it and return
        Optional<AiFeedback> existing = aiFeedbackRepository.findBySubmissionId(submissionId);
        if (existing.isPresent()) {
            notificationService.sendAiFeedback(submissionId.toString(),
                    new AiFeedbackMessage(submissionId.toString(), "AI_FEEDBACK",
                            existing.get().getFeedback(), existing.get().getModel(), Instant.now()));
            return;
        }

        // 3. Build the prompt
        String systemPrompt = "You are a competitive programming coach. Analyze the following "
                + "code submission and provide constructive feedback. Focus on: code correctness, "
                + "time/space complexity, code style, and potential improvements. Be concise and helpful.";

        String userPrompt = buildUserPrompt(submission);

        // 4. Call OpenAI API
        Map<String, Object> requestBody = Map.of(
                "model", aiConfig.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", aiConfig.getMaxTokens(),
                "temperature", aiConfig.getTemperature()
        );

        try {
            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // Parse the response to extract the assistant message and token usage
            JsonNode root = objectMapper.readTree(responseBody);
            String feedback = root.get("choices").get(0).get("message").get("content").asText();

            JsonNode usage = root.get("usage");
            int promptTokens = usage != null ? usage.get("prompt_tokens").asInt() : 0;
            int completionTokens = usage != null ? usage.get("completion_tokens").asInt() : 0;

            // 5. Persist feedback
            AiFeedback aiFeedback = new AiFeedback();
            aiFeedback.setSubmission(submission);
            aiFeedback.setFeedback(feedback);
            aiFeedback.setModel(aiConfig.getModel());
            aiFeedback.setPromptTokens(promptTokens);
            aiFeedback.setCompletionTokens(completionTokens);
            aiFeedbackRepository.save(aiFeedback);

            // 6. Push result to the frontend via WebSocket
            notificationService.sendAiFeedback(submissionId.toString(),
                    new AiFeedbackMessage(submissionId.toString(), "AI_FEEDBACK",
                            feedback, aiConfig.getModel(), Instant.now()));

        } catch (Exception e) {
            log.error("AI feedback failed for submission {}", submissionId, e);
            // Notify the frontend that feedback generation failed
            notificationService.sendAiFeedback(submissionId.toString(),
                    new AiFeedbackMessage(submissionId.toString(), "AI_FEEDBACK_ERROR",
                            "Failed to generate AI feedback: " + e.getMessage(), null, Instant.now()));
        }
    }

    /**
     * Assembles the user-facing portion of the prompt with all available context
     * about the submission: source code, verdict, timing, problem description,
     * test results, and any stderr output.
     */
    private String buildUserPrompt(Submission submission) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Code (Java)\n```java\n").append(submission.getSourceCode()).append("\n```\n\n");
        sb.append("## Verdict: ").append(submission.getVerdict()).append("\n");
        if (submission.getExecutionTimeMs() != null) {
            sb.append("## Execution Time: ").append(submission.getExecutionTimeMs()).append("ms\n");
        }
        if (submission.getProblem() != null) {
            sb.append("## Problem: ").append(submission.getProblem().getTitle()).append("\n");
            sb.append(submission.getProblem().getDescription()).append("\n");
        }
        if (submission.getPassedTestCases() != null && submission.getTotalTestCases() != null) {
            sb.append("## Test Cases: ").append(submission.getPassedTestCases())
                    .append("/").append(submission.getTotalTestCases()).append(" passed\n");
        }
        if (submission.getStderr() != null && !submission.getStderr().isBlank()) {
            sb.append("## Stderr:\n```\n").append(submission.getStderr()).append("\n```\n");
        }
        return sb.toString();
    }
}
