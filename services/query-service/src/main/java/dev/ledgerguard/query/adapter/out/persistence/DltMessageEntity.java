package dev.ledgerguard.query.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A message that exhausted the retry ladder, captured for operator inspection and replay.
 *
 * <p>The natural key is the origin coordinate — source topic, partition, offset — not the surrogate
 * id. A redelivery of the same dead letter is the same dead letter, and the unique index in
 * {@code V3__dlt_message.sql} enforces that.
 */
@Entity
@Table(name = "dlt_message")
public class DltMessageEntity {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "source_topic", nullable = false)
    private String sourceTopic;

    @Column(name = "partition_number", nullable = false)
    private int partitionNumber;

    @Column(name = "record_offset", nullable = false)
    private long recordOffset;

    @Column(nullable = false)
    private String reason;

    @Column(name = "stack_trace_digest")
    private String stackTraceDigest;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "original_envelope", nullable = false)
    private String originalEnvelope;

    @Column(name = "first_failed_at")
    private Instant firstFailedAt;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Set when an operator replays this message; null means it is still outstanding. */
    @Column(name = "replayed_at")
    private Instant replayedAt;

    protected DltMessageEntity() {}

    public DltMessageEntity(
            UUID messageId,
            String sourceTopic,
            int partitionNumber,
            long recordOffset,
            String reason,
            String stackTraceDigest,
            int attemptCount,
            String originalEnvelope,
            Instant firstFailedAt,
            Instant occurredAt,
            Instant recordedAt) {
        this.messageId = messageId;
        this.sourceTopic = sourceTopic;
        this.partitionNumber = partitionNumber;
        this.recordOffset = recordOffset;
        this.reason = reason;
        this.stackTraceDigest = stackTraceDigest;
        this.attemptCount = attemptCount;
        this.originalEnvelope = originalEnvelope;
        this.firstFailedAt = firstFailedAt;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public void markReplayed(Instant at) {
        this.replayedAt = at;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public int getPartitionNumber() {
        return partitionNumber;
    }

    public long getRecordOffset() {
        return recordOffset;
    }

    public String getReason() {
        return reason;
    }

    public String getStackTraceDigest() {
        return stackTraceDigest;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getOriginalEnvelope() {
        return originalEnvelope;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }
}
