package dev.ledgerguard.query.adapter.out.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Consumer-level dedupe for the Mongo projection (ADR-0006, layer 2).
 *
 * <p>The {@code _id} is {@code consumerGroup + ":" + eventId}, so uniqueness is enforced by the
 * primary key rather than by an application check — a check-then-act has a window between the two.
 *
 * <p>Never Redis: it loses its contents on restart, which is precisely when redelivery happens.
 */
@Document(collection = "processed_event_projection")
public class ProcessedEventDocument {

    @Id
    private String id;

    private String consumerGroup;
    private String eventId;
    private Instant processedAt;

    public ProcessedEventDocument() {}

    public ProcessedEventDocument(String consumerGroup, String eventId, Instant processedAt) {
        this.id = key(consumerGroup, eventId);
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public static String key(String consumerGroup, String eventId) {
        return consumerGroup + ':' + eventId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
