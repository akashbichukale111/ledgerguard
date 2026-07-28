package dev.ledgerguard.query.adapter.out.messaging;

import java.util.Objects;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import dev.ledgerguard.common.kafka.RetryPublisher;

/**
 * Publishes failed messages to the retry ladder or dead-letter topic.
 *
 * <p>Routes based on error classification: retryable errors go to retry.1/2/3, non-retryable go
 * straight to DLT. See ADR-0012.
 */
@Service
public class RetryPublishingService {
    private static final Logger log = LoggerFactory.getLogger(RetryPublishingService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public RetryPublishingService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
    }

    /**
     * Publish a message to retry ladder or DLT based on error classification.
     *
     * @param originalEnvelope original event JSON
     * @param attemptCount retry attempt number
     * @param reason human-readable error reason
     * @param ex exception that triggered the retry
     */
    public void publishRetryOrDlt(String originalEnvelope, int attemptCount, String reason, Throwable ex) {
        Objects.requireNonNull(originalEnvelope, "originalEnvelope");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(ex, "ex");

        // Extract source topic from original envelope (simplified: normally in message headers)
        String sourceTopic = extractSourceTopic(originalEnvelope);
        int partition = 0; // Normally from original message partition
        long offset = 0; // Normally from original message offset

        ProducerRecord<String, String> record =
                RetryPublisher.buildRetryRecord(sourceTopic, partition, offset, originalEnvelope, attemptCount, ex);

        kafkaTemplate.send(record).whenComplete((result, exception) -> {
            if (exception == null) {
                log.info(
                        "Published to {} for retry attempt {}",
                        result.getRecordMetadata().topic(),
                        attemptCount);
            } else {
                log.error("Failed to publish retry for attempt {}: {}", attemptCount, exception.getMessage());
            }
        });
    }

    private String extractSourceTopic(String originalEnvelope) {
        // Simplified: in production, parse from event envelope or headers
        // For now, default to transactions.events.v1
        return "transactions.events.v1";
    }
}
