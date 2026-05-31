package com.coderank.dto;

import com.coderank.model.enums.SubmissionStatus;
import com.coderank.model.enums.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response returned after submission creation or retrieval.
 *
 * @param wsChannel        STOMP topic path (e.g. "/topic/submissions/{id}") that the
 *                         client should subscribe to over WebSocket to receive real-time
 *                         execution progress updates. Included here so the client does
 *                         not need to construct topic URLs itself.
 * @param problemId        linked problem ID; null for playground submissions
 * @param passedTestCases  number of test cases that passed; null for playground
 * @param totalTestCases   total number of test cases; null for playground
 * @param testCaseResults  per-test-case details; only populated in WebSocket messages
 *                         during execution, null when fetched via GET (transient data)
 */
public record SubmissionResponse(
    UUID id,
    SubmissionStatus status,
    Verdict verdict,
    String sourceCode,
    String stdout,
    String stderr,
    Long executionTimeMs,
    Long memoryUsedKb,
    String errorMessage,
    String wsChannel,
    Instant createdAt,
    UUID problemId,
    Integer passedTestCases,
    Integer totalTestCases,
    List<TestCaseResultDto> testCaseResults
) {}
