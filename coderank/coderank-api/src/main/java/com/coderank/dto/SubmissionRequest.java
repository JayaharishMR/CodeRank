package com.coderank.dto;

import com.coderank.model.enums.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound payload for a code submission.
 *
 * @param language   required so the execution worker knows which runtime to invoke
 * @param sourceCode capped at 51 200 chars (~50 KB) to prevent abuse and keep
 *                   queue/database payloads bounded. NotBlank rejects empty
 *                   submissions early before they reach the worker.
 * @param stdin      optional program input; null when the program needs no input
 * @param problemId  nullable; null means playground mode (free-run), non-null
 *                   means judge mode (evaluated against the problem's test cases)
 */
public record SubmissionRequest(
    @NotNull Language language,
    @NotBlank @Size(max = 51200) String sourceCode,
    String stdin,
    UUID problemId
) {}
