package com.coderank.model;

import com.coderank.model.enums.Verdict;
import java.util.List;

/**
 * Aggregate result of judging a submission against all test cases for a problem.
 * The overall verdict is the first non-ACCEPTED verdict encountered (in test case
 * order), or ACCEPTED if every test case passes.
 */
public record JudgeResult(
    Verdict verdict,
    int passedTestCases,
    int totalTestCases,
    List<TestCaseResult> testCaseResults,
    Long totalExecutionTimeMs,
    String errorMessage
) {}
