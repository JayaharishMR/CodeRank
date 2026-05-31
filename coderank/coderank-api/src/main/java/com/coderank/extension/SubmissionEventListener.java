package com.coderank.extension;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;

/**
 * SPI (Service Provider Interface) for extending the submission lifecycle without
 * modifying core pipeline code. Any Spring bean implementing this interface is
 * automatically discovered and invoked by {@link SubmissionEventPublisher}.
 *
 * <p>All methods are {@code default} (no-op) so implementors only override the
 * events they care about — avoids forcing empty stub methods in every plugin.</p>
 *
 * <p><b>Phase 2 use case:</b> An AI-analysis plugin can implement
 * {@link #onSubmissionComplete} to send the code + result to an LLM for feedback
 * (code quality hints, complexity analysis) without touching the execution pipeline.</p>
 */
public interface SubmissionEventListener {

    /** Called right after the submission is received, before execution begins. */
    default void onSubmissionReceived(Submission submission) {}

    /** Called when execution finishes successfully (any verdict, including WRONG_ANSWER). */
    default void onSubmissionComplete(Submission submission, ExecutionResult result) {}

    /** Called when execution fails due to an infrastructure error (not a user code error). */
    default void onSubmissionFailed(Submission submission, String error) {}
}
