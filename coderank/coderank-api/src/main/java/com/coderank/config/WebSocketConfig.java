package com.coderank.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket (STOMP) configuration for real-time execution updates.
 *
 * Clients subscribe to per-submission topics to receive live output, status
 * changes, and final results as the code executes in a Docker container.
 * STOMP over WebSocket is used instead of plain WebSocket because STOMP
 * gives us topic-based pub/sub routing for free.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic -- clients subscribe here (e.g. /topic/submissions/{id})
        // to receive server-pushed execution events.
        config.enableSimpleBroker("/topic");
        // /app -- prefix for messages sent FROM the client to the server
        // (not currently used heavily, but keeps the namespace clean).
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/execution")
                .setAllowedOriginPatterns("*");
    }
}
