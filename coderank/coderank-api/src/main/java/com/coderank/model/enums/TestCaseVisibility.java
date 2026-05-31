package com.coderank.model.enums;

/**
 * Controls how much test case detail is revealed to the user after submission.
 */
public enum TestCaseVisibility {
    /** Show full input/output for the first failing test case to aid debugging. */
    SHOW_FIRST_FAILING,
    /** Only reveal sample test cases; hide all hidden test case details. */
    SHOW_SAMPLE_ONLY,
    /** Show no test case details at all; only the verdict is returned. */
    SHOW_NONE
}
