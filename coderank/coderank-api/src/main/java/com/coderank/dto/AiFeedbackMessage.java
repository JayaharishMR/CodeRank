package com.coderank.dto;

import java.time.Instant;

/**
 * WebSocket payload for AI feedback events. Sent to the same per-submission
 * topic used by execution updates so the frontend receives all submission-related
 * notifications on a single channel.
 *
 * @param type  discriminator field -- "AI_FEEDBACK" on success, "AI_FEEDBACK_ERROR" on failure
 * @param model the LLM model that produced the feedback; null on error
 */
public record AiFeedbackMessage(
    String submissionId,
    String type,
    String feedback,
    String model,
    Instant timestamp
) {}
