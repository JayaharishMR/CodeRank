package com.coderank.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ infrastructure for asynchronous code-execution pipeline.
 *
 * Submissions are queued rather than executed inline so the API can return
 * immediately and the execution workers can scale independently of the
 * web tier.
 */
@Configuration
public class RabbitMQConfig {
    public static final String SUBMISSION_QUEUE = "coderank.submissions";

    @Bean
    public Queue submissionQueue() {
        // Durable = true so pending submissions survive a RabbitMQ restart;
        // losing queued code submissions would silently drop user work.
        return new Queue(SUBMISSION_QUEUE, true);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        // Jackson converter lets us publish/consume POJOs directly instead of
        // manually serializing -- keeps producer and consumer in sync via the
        // shared DTO classes.
        return new Jackson2JsonMessageConverter();
    }
}
