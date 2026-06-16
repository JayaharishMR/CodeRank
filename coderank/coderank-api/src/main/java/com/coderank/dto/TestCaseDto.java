package com.coderank.dto;

/**
 * Lightweight DTO for sample test cases included in the problem response.
 * Only carries input and expected output — no verdict or timing information
 * since these are shown before any submission is made.
 */
public record TestCaseDto(
    String input,
    String expectedOutput
) {}
