package dev.ledgerguard.query.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import dev.ledgerguard.common.error.ErrorClassifier;
import dev.ledgerguard.query.adapter.out.messaging.RetryPublishingService;
import dev.ledgerguard.query.application.ProjectionService;

/**
 * Feeds the read model from Kafka.
 *
 * <p>Acknowledgement is <b>manual and after</b> projection: committing the offset first would mean a
 * crash between commit and projection loses the event permanently, converting at-least-once delivery
 * into at-most-once. The order here is the whole guarantee.
 *
 * <p>On projection failure, publishes to retry ladder or DLT based on error classification (ADR-0012).
 * Acknowledgement is always sent to move partition forward (non-blocking retry).
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private final ProjectionService projections;
    private final RetryPublishingService retryPublisher;

    public TransactionEventConsumer(ProjectionService projections, RetryPublishingService retryPublisher) {
        this.projections = projections;
        this.retryPublisher = retryPublisher;
    }

    @KafkaListener(
            topics = "${ledgerguard.kafka.topics.transactions:transactions.events.v1}",
            groupId = "${spring.kafka.consumer.group-id:query-service}")
    public void onTransactionEvent(String envelope, Acknowledgment acknowledgment) {
        try {
            projections.apply(envelope);
            acknowledgment.acknowledge();
        } catch (ProjectionService.NonRetryableProjectionException e) {
            // Permanent failure: route to DLT immediately, acknowledge to move partition forward
            log.error("non-retryable projection failure: {}", e.getMessage());
            var classification = ErrorClassifier.classify(e);
            retryPublisher.publishRetryOrDlt(envelope, 1, classification.reason(), e);
            acknowledgment.acknowledge();
        } catch (Throwable ex) {
            // Transient or unknown failure: route through retry ladder, acknowledge to move forward
            log.warn("projection error (will retry): {}", ex.getMessage());
            var classification = ErrorClassifier.classify(ex);
            retryPublisher.publishRetryOrDlt(envelope, 1, classification.reason(), ex);
            acknowledgment.acknowledge();
        }
    }
}
