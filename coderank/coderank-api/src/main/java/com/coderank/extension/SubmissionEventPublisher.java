package com.coderank.extension;

import com.coderank.model.ExecutionResult;
import com.coderank.model.Submission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dispatches submission lifecycle events to all registered {@link SubmissionEventListener}
 * beans. Acts as the bridge between the core execution pipeline and optional extensions.
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li>{@code @Autowired(required = false)} — if no listeners are registered (no plugins
 *       on the classpath), Spring injects {@code null} instead of failing to start. We
 *       fall back to an empty list so callers never need null-checks.</li>
 *   <li>Each listener invocation is wrapped in its own try-catch so a buggy extension
 *       can never break the core submission flow — it only logs and moves on.</li>
 * </ul>
 */
@Component
public class SubmissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventPublisher.class);

    private final List<SubmissionEventListener> listeners;

    // required = false: avoids NoSuchBeanDefinitionException when zero listeners exist.
    // The null-coalesce to List.of() keeps downstream code null-safe.
    public SubmissionEventPublisher(@Autowired(required = false) List<SubmissionEventListener> listeners) {
        this.listeners = listeners != null ? listeners : List.of();
    }

    public void publishReceived(Submission submission) {
        listeners.forEach(l -> {
            // Isolated try-catch: a failing extension must never prevent execution from proceeding.
            try { l.onSubmissionReceived(submission); }
            catch (Exception e) { log.error("Extension error on received", e); }
        });
    }

    public void publishComplete(Submission submission, ExecutionResult result) {
        listeners.forEach(l -> {
            try { l.onSubmissionComplete(submission, result); }
            catch (Exception e) { log.error("Extension error on complete", e); }
        });
    }

    public void publishFailed(Submission submission, String error) {
        listeners.forEach(l -> {
            try { l.onSubmissionFailed(submission, error); }
            catch (Exception e) { log.error("Extension error on failed", e); }
        });
    }
}
