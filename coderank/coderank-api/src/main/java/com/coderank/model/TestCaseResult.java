package com.coderank.model;

import com.coderank.model.enums.Verdict;

/**
 * Immutable value object carrying the result of evaluating a single test case.
 * Kept separate from the JPA {@link TestCase} entity so judge internals stay
 * decoupled from persistence concerns.
 */
public record TestCaseResult(
    int index,
    Verdict verdict,
    String actualOutput,
    String expectedOutput,
    String stderr,
    long executionTimeMs,
    boolean isSample
) {}
