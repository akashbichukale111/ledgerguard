package dev.ledgerguard.query.adapter.in.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import dev.ledgerguard.query.application.ProjectionService;

/**
 * Feeds the read model from Kafka.
 *
 * <p>Acknowledgement is <b>manual and after</b> projection: committing the offset first would mean a
 * crash between commit and projection loses the event permanently, converting at-least-once delivery
 * into at-most-once. The order here is the whole guarantee.
 *
 * <p>Kept deliberately thin. All logic lives in {@link ProjectionService} so it can be tested
 * without a broker; this class only handles delivery mechanics.
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private final ProjectionService projections;

    public TransactionEventConsumer(ProjectionService projections) {
        this.projections = projections;
    }

    @KafkaListener(
            topics = "${ledgerguard.kafka.topics.transactions:transactions.events.v1}",
            groupId = "${spring.kafka.consumer.group-id:query-service}")
    public void onTransactionEvent(String envelope, Acknowledgment acknowledgment) {
        try {
            projections.apply(envelope);
            acknowledgment.acknowledge();
        } catch (ProjectionService.NonRetryableProjectionException e) {
            // Deterministic failure: retrying wastes the budget and delays discovery. Acknowledge so
            // the partition is not stalled. DLT routing is Phase 7 — until then this is logged at
            // ERROR, which means a human must act.
            log.error("non-retryable projection failure, skipping event: {}", e.getMessage());
            acknowledgment.acknowledge();
        }
    }
}
