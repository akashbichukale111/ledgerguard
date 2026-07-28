package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Consumer-level dedupe (ADR-0006, layer 2).
 *
 * <p>Written in the <b>same transaction</b> as the state change it guards. Writing it separately
 * re-opens exactly the window it exists to close: a crash between the two would leave the effect
 * applied but unrecorded, and redelivery would apply it again.
 *
 * <p>Never Redis, never an in-memory cache — both lose their contents on restart, which is
 * precisely when redelivery happens.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {}

    public ProcessedEventEntity(String consumerGroup, UUID eventId, Instant now) {
        this.key = new Key(consumerGroup, eventId);
        this.processedAt = now;
    }

    public Key key() {
        return key;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "consumer_group", nullable = false)
        private String consumerGroup;

        @Column(name = "event_id", nullable = false)
        private UUID eventId;

        protected Key() {}

        public Key(String consumerGroup, UUID eventId) {
            this.consumerGroup = consumerGroup;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(consumerGroup, other.consumerGroup)
                    && Objects.equals(eventId, other.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, eventId);
        }
    }
}
