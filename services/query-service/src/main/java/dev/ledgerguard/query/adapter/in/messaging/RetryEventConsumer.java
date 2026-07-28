package dev.ledgerguard.query.adapter.in.messaging;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import dev.ledgerguard.query.adapter.out.messaging.RetryPublishingService;
import dev.ledgerguard.query.application.ProjectionService;

/**
 * Consumes messages from retry topics (retry.1, retry.2, retry.3) and reprocesses them.
 *
 * <p>On failure, publishes to the next retry topic or DLT based on error classification. Always
 * acknowledges to move partition forward (non-blocking retry). See ADR-0012.
 */
@Service
public class RetryEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(RetryEventConsumer.class);

    private final ProjectionService projections;
    private final RetryPublishingService retryPublisher;

    public RetryEventConsumer(ProjectionService projections, RetryPublishingService retryPublisher) {
        this.projections = Objects.requireNonNull(projections, "projections");
        this.retryPublisher = Objects.requireNonNull(retryPublisher, "retryPublisher");
    }

    @KafkaListener(
            topics = "${ledgerguard.retry.topics.1:transactions.events.v1.retry.1}",
            groupId = "${spring.application.name}-retry-1")
    void consumeRetry1(
            @Payload String retryJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        processRetry(retryJson, topic, offset, 2, acknowledgment);
    }

    @KafkaListener(
            topics = "${ledgerguard.retry.topics.2:transactions.events.v1.retry.2}",
            groupId = "${spring.application.name}-retry-2")
    void consumeRetry2(
            @Payload String retryJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        processRetry(retryJson, topic, offset, 3, acknowledgment);
    }

    @KafkaListener(
            topics = "${ledgerguard.retry.topics.3:transactions.events.v1.retry.3}",
            groupId = "${spring.application.name}-retry-3")
    void consumeRetry3(
            @Payload String retryJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        processRetry(retryJson, topic, offset, 4, acknowledgment);
    }

    private void processRetry(
            String retryJson, String topic, long offset, int nextAttempt, Acknowledgment acknowledgment) {
        try {
            var originalEnvelope = extractOriginalEnvelope(retryJson);

            log.info("Reprocessing retry from {} (offset={})", topic, offset);

            try {
                boolean applied = projections.apply(originalEnvelope);
                if (applied) {
                    log.info("Retry successful: {} now applied", topic);
                } else {
                    log.debug("Retry duplicate or stale: {} skipped (idempotent)", topic);
                }
            } catch (Throwable ex) {
                log.warn("Retry failed: {}", ex.getMessage());
                retryPublisher.publishRetryOrDlt(originalEnvelope, nextAttempt, "Retry failed: " + ex.getMessage(), ex);
            }
        } catch (Exception parseEx) {
            log.error("Failed to parse retry envelope from topic {}: {}", topic, parseEx.getMessage());
        } finally {
            // Always acknowledge to move partition forward (non-blocking retry)
            acknowledgment.acknowledge();
        }
    }

    private String extractOriginalEnvelope(String json) {
        // Simplified: real implementation uses ObjectMapper
        // For now, extract fields from JSON string manually
        var originalStart = json.indexOf("\"originalEnvelope\":\"") + "\"originalEnvelope\":\"".length();
        var originalEnd = json.indexOf("\"", originalStart + 1);
        return json.substring(originalStart, originalEnd);
    }
}
