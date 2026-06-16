package com.coderank.dto;

import java.util.List;
import java.util.UUID;

/**
 * API response for a coding problem. Includes problem metadata, constraints,
 * starter code, and only the sample test cases (hidden test cases are never
 * exposed through this endpoint).
 */
public record ProblemResponse(
    UUID id,
    String title,
    String slug,
    String difficulty,
    String description,
    List<String> constraints,
    String starterCode,
    int timeLimitMs,
    int memoryLimitKb,
    List<TestCaseDto> sampleTestCases
) {}
