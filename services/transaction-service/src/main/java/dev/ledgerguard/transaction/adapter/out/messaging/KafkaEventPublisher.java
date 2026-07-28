package dev.ledgerguard.transaction.adapter.out.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;

import dev.ledgerguard.contracts.EventEnvelope;
import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRecordEntity;

/** Wraps an outbox record in the versioned envelope and sends it to Kafka. *
 * <p>Constructed by TransactionServiceConfig, not component-scanned: the topic name comes from configuration and cannot be autowired as a bare String.
 */
public class KafkaEventPublisher implements EventPublisher {

    /** Bounded so a broker that accepts the connection but never responds cannot wedge the poller. */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String topic;

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafka, ObjectMapper objectMapper, Clock clock, String topic) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.topic = topic;
    }

    @Override
    public void publish(OutboxRecordEntity record) {
        try {
            JsonNode storedHeaders = objectMapper.readTree(record.headers());

            EventEnvelope envelope = new EventEnvelope(
                    record.eventId(),
                    record.eventType(),
                    record.eventVersion(),
                    record.aggregateType(),
                    record.aggregateId(),
                    0L,
                    record.occurredAt(),
                    clock.instant(),
                    UUID.fromString(storedHeaders.path("correlationId").asText()),
                    optionalUuid(storedHeaders.path("causationId").asText()),
                    new EventEnvelope.Actor(
                            storedHeaders.path("actorSubject").asText("system"),
                            EventEnvelope.Actor.Role.SYSTEM,
                            EventEnvelope.Actor.Source.API),
                    emptyToNull(storedHeaders.path("traceparent").asText()),
                    storedHeaders.path("schemaRef").asText(),
                    record.payload());

            // Keyed by aggregateId: all events for one transaction land on one partition and are
            // therefore consumed in order. There is no global ordering, and nothing may assume one.
            ProducerRecord<String, String> message = new ProducerRecord<>(
                    topic, record.aggregateId().toString(), objectMapper.writeValueAsString(envelope));

            // Kafka headers carry the trace context across the broker hop. This does NOT happen
            // automatically; without it a traceId stops at the producer and the "one trace across
            // four services" claim would be false.
            addHeader(message, "correlationId", envelope.correlationId().toString());
            addHeader(message, "eventType", envelope.eventType());
            addHeader(message, "eventId", envelope.eventId().toString());
            if (envelope.traceparent() != null) {
                addHeader(message, "traceparent", envelope.traceparent());
            }

            // Block on the ack. Returning before the broker confirms would let the poller mark the
            // record published when it is not.
            kafka.send(message).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise envelope for " + record.eventId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("interrupted publishing " + record.eventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException("broker did not acknowledge " + record.eventId(), e);
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
        record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID optionalUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
