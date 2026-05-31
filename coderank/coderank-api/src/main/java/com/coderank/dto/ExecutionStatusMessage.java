package com.coderank.dto;

import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import java.time.Instant;
import java.util.List;

/**
 * Payload pushed to WebSocket subscribers whenever a submission's execution
 * state changes (e.g. QUEUED -> RUNNING -> COMPLETED). The worker publishes
 * this to the STOMP topic so the frontend can update the UI without polling.
 *
 * Uses submissionId as a String (not UUID) because STOMP message serialisation
 * is simpler with plain strings and the client only needs it for correlation.
 *
 * @param passedTestCases  judge mode only; null for playground submissions
 * @param totalTestCases   judge mode only; null for playground submissions
 * @param testCaseResults  per-test-case results filtered by the problem's visibility
 *                         setting; null for playground submissions
 */
public record ExecutionStatusMessage(
    String id,
    SubmissionStatus status,
    Verdict verdict,
    String stdout,
    String stderr,
    Long executionTimeMs,
    Long memoryUsedKb,
    String errorMessage,
    Instant timestamp,
    Integer passedTestCases,
    Integer totalTestCases,
    List<TestCaseResultDto> testCaseResults
) {}
