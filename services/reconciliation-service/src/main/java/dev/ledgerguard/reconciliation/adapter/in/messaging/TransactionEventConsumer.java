package dev.ledgerguard.reconciliation.adapter.in.messaging;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.ledgerguard.common.observability.KafkaTracingConsumer;
import dev.ledgerguard.common.observability.MdcContext;
import dev.ledgerguard.reconciliation.adapter.out.messaging.ReconciliationEventPublisher;
import dev.ledgerguard.reconciliation.adapter.out.persistence.ProcessedEventEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.ProcessedEventRepository;
import dev.ledgerguard.reconciliation.application.ReconcileTransactionHandler;
import dev.ledgerguard.reconciliation.domain.matching.TransactionReconciledPayload;

/**
 * Consumes {@code TransactionReceived} and publishes the reconciliation outcome.
 *
 * <p>This closes the loop. Until it existed reconciliation-service had no inbound adapter at all:
 * the matching engine and the saga were real, well-tested code that nothing ever called at runtime,
 * so no transaction was ever reconciled and the read model never left {@code RECEIVED}.
 *
 * <p><b>Dedupe is written in the same transaction as the effect</b> ({@code processed_event},
 * ADR-0006). Recording it separately would reopen exactly the window it exists to close: a crash
 * between the publish and the dedupe write would reconcile the same transaction twice.
 */
@Service
public class TransactionEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private static final String CONSUMER_GROUP = "reconciliation-service";
    private static final String HANDLED_EVENT_TYPE = "TransactionReceived";

    private final ReconcileTransactionHandler handler;
    private final ReconciliationEventPublisher publisher;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransactionEventConsumer(
            ReconcileTransactionHandler handler,
            ReconciliationEventPublisher publisher,
            ProcessedEventRepository processedEvents,
            ObjectMapper objectMapper,
            Clock clock) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @KafkaListener(topics = "${ledgerguard.topics.transactions:transactions.events.v1}", groupId = CONSUMER_GROUP)
    @Transactional
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            KafkaTracingConsumer.populateMdcFromHeaders(record);

            JsonNode envelope = objectMapper.readTree(record.value());
            String eventType = envelope.path("eventType").asText();

            // The topic carries every transaction event, not only the one this service acts on.
            if (!HANDLED_EVENT_TYPE.equals(eventType)) {
                log.debug("ignoring {} — not handled by this consumer", eventType);
                acknowledgment.acknowledge();
                return;
            }

            UUID eventId = UUID.fromString(envelope.path("eventId").asText());
            MdcContext.put(MdcContext.EVENT_TYPE, eventType);

            if (processedEvents.existsById(new ProcessedEventEntity.Key(CONSUMER_GROUP, eventId))) {
                // At-least-once delivery means this is normal, not exceptional.
                log.debug("skipping duplicate event {}", eventId);
                acknowledgment.acknowledge();
                return;
            }

            JsonNode payload = objectMapper.readTree(envelope.path("payload").asText());
            TransactionReconciledPayload outcome = handler.reconcile(payload);

            publisher.publish(
                    outcome,
                    UUID.fromString(envelope.path("correlationId").asText()),
                    // The received event is what directly caused this outcome; recording it is what
                    // lets the audit trail join the two halves of the story.
                    eventId,
                    envelope.path("traceparent").asText(null));

            processedEvents.save(new ProcessedEventEntity(CONSUMER_GROUP, eventId, clock.instant()));

            // Acknowledged only on a path that completed. Acking in a finally block would advance
            // the offset even when the work threw, so the container's error handler would never see
            // the record again and the failure would be silent.
            acknowledgment.acknowledge();

        } catch (Exception e) {
            // Rethrow so the transaction rolls back and the container's error handler decides
            // whether to retry or dead-letter. Swallowing here would drop the transaction and leave
            // the read model stuck at RECEIVED with no trace of why.
            log.error("failed to reconcile record at {}-{}", record.topic(), record.offset(), e);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);

        } finally {
            // Cleared unconditionally: a leaked MDC field would label the next message on this
            // thread with the previous message's identifiers.
            KafkaTracingConsumer.clearMdc();
        }
    }
}
