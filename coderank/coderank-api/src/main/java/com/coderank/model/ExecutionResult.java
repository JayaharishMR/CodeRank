package com.coderank.model;

import com.coderank.model.enums.Verdict;

/**
 * Immutable value object carrying the raw output from the sandboxed code
 * execution environment. Used to decouple sandbox internals from the
 * persistence layer -- the service maps this into a {@link Submission} entity.
 *
 * <p>Fields like {@code memoryUsedKb} are nullable because the sandbox may
 * not be able to measure memory for processes that crash or are killed
 * before stats are collected.</p>
 */
public record ExecutionResult(
    Verdict verdict,
    String stdout,
    String stderr,
    Long executionTimeMs,
    Long memoryUsedKb,
    String errorMessage
) {}
