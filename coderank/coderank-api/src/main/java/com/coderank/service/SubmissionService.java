package com.coderank.service;

import com.coderank.dto.SubmissionRequest;
import com.coderank.model.Problem;
import com.coderank.model.Submission;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.repository.ProblemRepository;
import com.coderank.repository.SubmissionRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

/**
 * Handles submission intake: persists the submission to the DB, then enqueues it
 * for asynchronous execution via RabbitMQ. This two-step (save then enqueue) approach
 * guarantees the submission exists in the DB before the worker picks it up, avoiding
 * race conditions where the worker could receive a message for a non-existent row.
 */
@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;
    private final RabbitTemplate rabbitTemplate;

    public SubmissionService(SubmissionRepository submissionRepository,
                             ProblemRepository problemRepository,
                             RabbitTemplate rabbitTemplate) {
        this.submissionRepository = submissionRepository;
        this.problemRepository = problemRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    public Submission submit(SubmissionRequest request, String keycloakUserId) {
        Submission submission = new Submission();
        submission.setLanguage(request.language());
        submission.setSourceCode(request.sourceCode());
        submission.setStdin(request.stdin());
        submission.setStatus(SubmissionStatus.QUEUED);

        // If submitting against a problem, link the submission to the problem.
        // stdin is ignored in judge mode -- test cases provide the input.
        if (request.problemId() != null) {
            Problem problem = problemRepository.findById(request.problemId())
                    .orElseThrow(() -> new RuntimeException("Problem not found: " + request.problemId()));
            submission.setProblem(problem);
        }

        // Guest users (unauthenticated) have no keycloakUserId, so userId stays null.
        // The worker uses this later to decide whether to persist results (guests are ephemeral).
        if (keycloakUserId != null) {
            // TODO: Look up or create the internal User entity from the Keycloak subject ID.
            //       Until this is wired up, even authenticated users behave like guests.
        }

        // Save first so the row exists before the worker can pick up the message.
        submission = submissionRepository.save(submission);

        // Enqueue only the ID — the worker fetches the full entity from the DB,
        // keeping the message payload small and avoiding serialization coupling.
        rabbitTemplate.convertAndSend("coderank.submissions", submission.getId().toString());
        return submission;
    }

    public List<Submission> getRecentSubmissions() {
        return submissionRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50)).getContent();
    }

    public Submission getById(UUID id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Submission not found: " + id));
    }
}
