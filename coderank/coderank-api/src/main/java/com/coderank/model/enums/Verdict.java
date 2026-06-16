package com.coderank.model.enums;

/**
 * Final judgment of a submission's execution outcome.
 */
public enum Verdict {
    /** Code compiled, ran within limits, and produced correct output. */
    ACCEPTED,
    /** Code ran successfully but output did not match expected results. */
    WRONG_ANSWER,
    /** Execution exceeded the allowed wall-clock time and was killed. */
    TIME_LIMIT_EXCEEDED,
    /** Process consumed more memory than the sandbox permits. */
    MEMORY_LIMIT_EXCEEDED,
    /** Process crashed during execution (e.g. NullPointerException, segfault). */
    RUNTIME_ERROR,
    /** Source code failed to compile -- see stderr for compiler diagnostics. */
    COMPILATION_ERROR,
    /** Submission is still being processed; not a final verdict. */
    PENDING
}
