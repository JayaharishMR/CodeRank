package com.coderank.controller;

import com.coderank.dto.SubmissionRequest;
import com.coderank.dto.SubmissionResponse;
import com.coderank.model.Submission;
import com.coderank.service.SubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Handles code submission lifecycle: accepting new submissions and
 * retrieving results. Supports both authenticated and guest users.
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * Accepts a code submission for asynchronous execution.
     *
     * Returns 202 (Accepted) instead of 200 because the actual code execution
     * happens asynchronously via a worker -- the response only confirms the
     * submission was queued, not that it finished.
     *
     * @param jwt nullable because guests (unauthenticated users) can submit code
     *            too. Spring Security's filter chain is configured to allow
     *            anonymous access to this endpoint, so the JWT may be absent.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionResponse submit(
            @Valid @RequestBody SubmissionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        // Extract user identity when present; null signals a guest submission
        String userId = (jwt != null) ? jwt.getSubject() : null;
        Submission submission = submissionService.submit(request, userId);
        return toResponse(submission);
    }

    @GetMapping
    public List<SubmissionResponse> listSubmissions() {
        return submissionService.getRecentSubmissions().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public SubmissionResponse getSubmission(@PathVariable UUID id) {
        Submission submission = submissionService.getById(id);
        return toResponse(submission);
    }

    private SubmissionResponse toResponse(Submission s) {
        return new SubmissionResponse(
                s.getId(), s.getStatus(), s.getVerdict(),
                s.getSourceCode(), s.getStdout(), s.getStderr(),
                s.getExecutionTimeMs(), s.getMemoryUsedKb(),
                s.getErrorMessage(),
                // wsChannel: tells the client which STOMP topic to subscribe to
                // for real-time execution updates on this specific submission
                "/topic/submissions/" + s.getId(),
                s.getCreatedAt(),
                s.getProblem() != null ? s.getProblem().getId() : null,
                s.getPassedTestCases(),
                s.getTotalTestCases(),
                null  // test case results only available via WebSocket during execution
        );
    }
}
