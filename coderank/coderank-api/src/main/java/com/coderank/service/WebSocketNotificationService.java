package com.coderank.service;

import com.coderank.dto.AiFeedbackMessage;
import com.coderank.dto.ExecutionStatusMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Pushes real-time execution status updates to browser clients over STOMP/WebSocket.
 * Each submission gets its own topic (/topic/submissions/{id}) so the frontend only
 * subscribes to the specific submission it cares about, avoiding noisy broadcasts.
 */
@Service
public class WebSocketNotificationService {
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketNotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Publishes to /topic/submissions/{submissionId}. The frontend subscribes to this
     * per-submission topic after POSTing, so it receives COMPILING -> RUNNING -> COMPLETED
     * transitions as they happen, enabling a live status indicator.
     */
    public void sendStatusUpdate(String submissionId, ExecutionStatusMessage message) {
        messagingTemplate.convertAndSend("/topic/submissions/" + submissionId, message);
    }

    /**
     * Publishes AI-generated feedback (or an error) to the same per-submission topic.
     * The frontend distinguishes feedback from status updates via the {@code type} field
     * in the message payload.
     */
    public void sendAiFeedback(String submissionId, AiFeedbackMessage message) {
        messagingTemplate.convertAndSend("/topic/submissions/" + submissionId, message);
    }
}
