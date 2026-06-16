package com.coderank.controller;

import com.coderank.dto.AiFeedbackMessage;
import com.coderank.model.AiFeedback;
import com.coderank.repository.AiFeedbackRepository;
import com.coderank.service.AiFeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for requesting and retrieving AI-generated feedback on submissions.
 *
 * POST triggers an async feedback request (returns 202 Accepted immediately).
 * GET returns previously computed feedback synchronously.
 *
 * When AI is disabled ({@code coderank.ai.enabled=false}), the service bean is
 * absent and POST returns a clear error instead of silently failing.
 */
@RestController
@RequestMapping("/api/v1/submissions/{submissionId}/feedback")
public class AiFeedbackController {

    // Optional injection -- null when coderank.ai.enabled=false because
    // AiFeedbackService is @ConditionalOnProperty and won't be loaded.
    @Autowired(required = false)
    private AiFeedbackService aiFeedbackService;

    private final AiFeedbackRepository aiFeedbackRepository;

    public AiFeedbackController(AiFeedbackRepository aiFeedbackRepository) {
        this.aiFeedbackRepository = aiFeedbackRepository;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> requestFeedback(@PathVariable UUID submissionId) {
        if (aiFeedbackService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI feedback is not enabled"));
        }
        aiFeedbackService.requestFeedback(submissionId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "processing"));
    }

    @GetMapping
    public ResponseEntity<AiFeedbackMessage> getFeedback(@PathVariable UUID submissionId) {
        return aiFeedbackRepository.findBySubmissionId(submissionId)
                .map(feedback -> ResponseEntity.ok(new AiFeedbackMessage(
                        submissionId.toString(), "AI_FEEDBACK",
                        feedback.getFeedback(), feedback.getModel(), feedback.getCreatedAt())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
