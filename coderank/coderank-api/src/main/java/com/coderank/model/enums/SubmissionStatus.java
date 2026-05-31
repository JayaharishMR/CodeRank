package com.coderank.model.enums;

/**
 * Tracks where a submission is in the processing pipeline.
 * Follows a linear state machine:
 *   QUEUED -> COMPILING -> RUNNING -> COMPLETED
 *                |           |
 *                +--> FAILED <+
 *
 * FAILED is a terminal state reachable from any processing stage
 * (e.g. compilation error, sandbox crash, internal timeout).
 * COMPLETED means execution finished -- check {@link Verdict} for the outcome.
 */
public enum SubmissionStatus {
    QUEUED, COMPILING, RUNNING, COMPLETED, FAILED
}
