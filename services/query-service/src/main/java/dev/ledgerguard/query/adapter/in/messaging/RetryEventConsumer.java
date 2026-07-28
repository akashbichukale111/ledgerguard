package dev.ledgerguard.query.adapter.in.messaging;

import java.util.Objects;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import dev.ledgerguard.common.kafka.RetryEnvelopeCodec;
import dev.ledgerguard.common.kafka.RetryPublisher;
import dev.ledgerguard.common.observability.KafkaTracingConsumer;
import dev.ledgerguard.common.observability.MdcContext;
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
    void consumeRetry1(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processRetry(record, 2, acknowledgment);
    }

    @KafkaListener(
            topics = "${ledgerguard.retry.topics.2:transactions.events.v1.retry.2}",
            groupId = "${spring.application.name}-retry-2")
    void consumeRetry2(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processRetry(record, 3, acknowledgment);
    }

    @KafkaListener(
            topics = "${ledgerguard.retry.topics.3:transactions.events.v1.retry.3}",
            groupId = "${spring.application.name}-retry-3")
    void consumeRetry3(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processRetry(record, 4, acknowledgment);
    }

    private void processRetry(ConsumerRecord<String, String> record, int nextAttempt, Acknowledgment acknowledgment) {
        try {
            KafkaTracingConsumer.populateMdcFromHeaders(record);
            MdcContext.put(MdcContext.EVENT_TYPE, "retry");

            String originalEnvelope;
            try {
                originalEnvelope = extractOriginalEnvelope(record.value());
            } catch (Exception parseEx) {
                // An unreadable retry message cannot be reprocessed, but dropping it loses the
                // payload for good. Push the raw record to the DLT so an operator can still see and
                // replay it, then stop — there is nothing to hand the projection.
                log.error("Failed to parse retry envelope from topic {}: {}", record.topic(), parseEx.getMessage());
                republish(
                        record.value(),
                        RetryPublisher.DLT_ATTEMPT,
                        "Unparseable retry envelope from " + record.topic(),
                        parseEx);
                return;
            }

            log.info("Reprocessing retry from {} (offset={})", record.topic(), record.offset());

            try {
                boolean applied = projections.apply(originalEnvelope);
                if (applied) {
                    log.info("Retry successful: {} now applied", record.topic());
                } else {
                    log.debug("Retry duplicate or stale: {} skipped (idempotent)", record.topic());
                }
            } catch (Exception ex) {
                log.warn("Retry failed: {}", ex.getMessage());
                republish(originalEnvelope, nextAttempt, "Retry failed: " + ex.getMessage(), ex);
            }
        } finally {
            // The offset always moves. A message that cannot be advanced is still acknowledged, so a
            // single bad record never stalls the partition head (ADR-0012).
            KafkaTracingConsumer.clearMdc();
            acknowledgment.acknowledge();
        }
    }

    /**
     * Hands a message to the next rung of the ladder. A broker-side failure here is logged rather
     * than rethrown: the caller is mid-cleanup and rethrowing would only block the offset.
     */
    private void republish(String payload, int attempt, String reason, Exception cause) {
        try {
            retryPublisher.publishRetryOrDlt(payload, attempt, reason, cause);
        } catch (RuntimeException publishEx) {
            log.error("Could not publish to retry/DLT for attempt {}", attempt, publishEx);
        }
    }

    private String extractOriginalEnvelope(String json) throws java.io.IOException {
        return RetryEnvelopeCodec.originalEnvelopeOf(json);
    }
}
