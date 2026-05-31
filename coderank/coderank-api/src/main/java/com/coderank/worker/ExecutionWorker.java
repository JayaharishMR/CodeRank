package com.coderank.worker;

import com.coderank.config.RabbitMQConfig;
import com.coderank.dto.ExecutionStatusMessage;
import com.coderank.dto.TestCaseResultDto;
import com.coderank.extension.SubmissionEventPublisher;
import com.coderank.judge.JudgeService;
import com.coderank.model.ExecutionResult;
import com.coderank.model.JudgeResult;
import com.coderank.model.Problem;
import com.coderank.model.Submission;
import com.coderank.model.TestCaseResult;
import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.TestCaseVisibility;
import com.coderank.model.enums.Verdict;
import com.coderank.repository.SubmissionRepository;
import com.coderank.service.WebSocketNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RabbitMQ consumer that dequeues submission IDs and orchestrates the execution pipeline.
 * Routes between two modes:
 * <ul>
 *   <li><b>Judge mode</b> (submission linked to a problem): compiles once, runs against
 *       all test cases via {@link JudgeService}, applies visibility filtering.</li>
 *   <li><b>Playground mode</b> (no problem): runs code with optional stdin in the
 *       Docker sandbox and returns raw output.</li>
 * </ul>
 *
 * Status is saved and broadcast at each stage so the frontend can show a live progress
 * indicator (e.g., spinner changes from "Compiling..." to "Running...").
 */
@Component
public class ExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWorker.class);

    private final SubmissionRepository submissionRepository;
    private final DockerSandboxManager sandboxManager;
    private final WebSocketNotificationService notificationService;
    private final JudgeService judgeService;
    private final SubmissionEventPublisher eventPublisher;

    public ExecutionWorker(SubmissionRepository submissionRepository,
                           DockerSandboxManager sandboxManager,
                           WebSocketNotificationService notificationService,
                           JudgeService judgeService,
                           SubmissionEventPublisher eventPublisher) {
        this.submissionRepository = submissionRepository;
        this.sandboxManager = sandboxManager;
        this.notificationService = notificationService;
        this.judgeService = judgeService;
        this.eventPublisher = eventPublisher;
    }

    @RabbitListener(queues = RabbitMQConfig.SUBMISSION_QUEUE)
    public void processSubmission(String submissionIdStr) {
        UUID submissionId = UUID.fromString(submissionIdStr);
        log.info("Processing submission: {}", submissionId);

        // Re-fetch from DB because the message only carries the ID (lightweight message payload).
        Submission submission = submissionRepository.findByIdWithProblem(submissionId).orElse(null);
        if (submission == null) {
            // Possible if the row was deleted between enqueue and dequeue; safe to discard.
            log.warn("Submission not found: {}", submissionId);
            return;
        }

        eventPublisher.publishReceived(submission);

        try {
            if (submission.getProblem() != null) {
                // Judge mode: compile once, run against all test cases
                updateStatus(submission, SubmissionStatus.COMPILING);
                updateStatus(submission, SubmissionStatus.RUNNING);

                JudgeResult result = judgeService.judge(submission, submission.getProblem());

                submission.setStatus(SubmissionStatus.COMPLETED);
                submission.setVerdict(result.verdict());
                submission.setPassedTestCases(result.passedTestCases());
                submission.setTotalTestCases(result.totalTestCases());
                submission.setExecutionTimeMs(result.totalExecutionTimeMs());
                submission.setErrorMessage(result.errorMessage());

                submissionRepository.save(submission);
                sendNotification(submission, result.testCaseResults());

                eventPublisher.publishComplete(submission, new ExecutionResult(
                        result.verdict(), null, null, result.totalExecutionTimeMs(), null, result.errorMessage()));

                log.info("Submission {} judged: {} ({}/{})", submissionId, result.verdict(),
                        result.passedTestCases(), result.totalTestCases());
            } else {
                // Playground mode: existing flow
                updateStatus(submission, SubmissionStatus.COMPILING);
                updateStatus(submission, SubmissionStatus.RUNNING);

                // Delegate the actual compile+run to the Docker sandbox.
                ExecutionResult result = sandboxManager.execute(submission);

                submission.setStatus(SubmissionStatus.COMPLETED);
                submission.setVerdict(result.verdict());
                submission.setStdout(result.stdout());
                submission.setStderr(result.stderr());
                submission.setExecutionTimeMs(result.executionTimeMs());
                submission.setMemoryUsedKb(result.memoryUsedKb());
                submission.setErrorMessage(result.errorMessage());

                submissionRepository.save(submission);

                // Final WebSocket push with the verdict and output.
                sendNotification(submission, null);

                eventPublisher.publishComplete(submission, result);

                log.info("Submission {} completed: {}", submissionId, result.verdict());
            }

        } catch (Exception e) {
            log.error("Failed to process submission: {}", submissionId, e);
            submission.setStatus(SubmissionStatus.FAILED);
            submission.setErrorMessage(e.getMessage());
            submissionRepository.save(submission);
            // Still notify so the frontend can show the error instead of hanging.
            sendNotification(submission, null);
            eventPublisher.publishFailed(submission, e.getMessage());
        }
    }

    /** Persists the status change and immediately broadcasts it over WebSocket. */
    private void updateStatus(Submission submission, SubmissionStatus status) {
        submission.setStatus(status);
        submissionRepository.save(submission);
        sendNotification(submission, null);
    }

    private void sendNotification(Submission submission, List<TestCaseResult> testCaseResults) {
        List<TestCaseResultDto> dtos = null;
        if (testCaseResults != null) {
            // Apply visibility filter based on problem's testCaseVisibility setting
            dtos = filterTestCaseResults(testCaseResults, submission.getProblem());
        }

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
                        Instant.now(),
                        submission.getPassedTestCases(),
                        submission.getTotalTestCases(),
                        dtos
                )
        );
    }

    /**
     * Filters per-test-case results according to the problem's visibility policy.
     * This prevents leaking hidden test case inputs/outputs to the client while
     * still providing useful debugging information for sample and first-failing cases.
     */
    private List<TestCaseResultDto> filterTestCaseResults(List<TestCaseResult> results, Problem problem) {
        if (problem == null) return null;

        TestCaseVisibility visibility = problem.getTestCaseVisibility();

        return switch (visibility) {
            case SHOW_NONE -> results.stream()
                    .map(r -> new TestCaseResultDto(r.index(), r.verdict(), null, null, null, r.executionTimeMs(), r.isSample()))
                    .toList();
            case SHOW_SAMPLE_ONLY -> results.stream()
                    .map(r -> {
                        if (r.isSample()) {
                            return new TestCaseResultDto(r.index(), r.verdict(), r.actualOutput(), r.expectedOutput(), null, r.executionTimeMs(), true);
                        }
                        return new TestCaseResultDto(r.index(), r.verdict(), null, null, null, r.executionTimeMs(), false);
                    })
                    .toList();
            case SHOW_FIRST_FAILING -> {
                boolean[] firstFailingFound = {false};
                List<TestCaseResultDto> filtered = new ArrayList<>();
                for (TestCaseResult r : results) {
                    if (r.isSample()) {
                        filtered.add(new TestCaseResultDto(r.index(), r.verdict(), r.actualOutput(), r.expectedOutput(), null, r.executionTimeMs(), true));
                    } else if (!firstFailingFound[0] && r.verdict() != Verdict.ACCEPTED) {
                        firstFailingFound[0] = true;
                        filtered.add(new TestCaseResultDto(r.index(), r.verdict(), r.actualOutput(), r.expectedOutput(), null, r.executionTimeMs(), false));
                    } else {
                        filtered.add(new TestCaseResultDto(r.index(), r.verdict(), null, null, null, r.executionTimeMs(), false));
                    }
                }
                yield filtered;
            }
        };
    }
}
