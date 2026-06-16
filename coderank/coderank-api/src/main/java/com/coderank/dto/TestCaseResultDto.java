package com.coderank.dto;

import com.coderank.model.enums.Verdict;

/**
 * Outbound representation of a single test case evaluation result.
 * Visibility filtering (controlled by {@link com.coderank.model.enums.TestCaseVisibility})
 * determines which fields are populated vs. nulled out before sending to the client.
 */
public record TestCaseResultDto(
    int index,
    Verdict verdict,
    String actualOutput,
    String expectedOutput,
    String input,
    long executionTimeMs,
    boolean isSample
) {}
