package dev.ledgerguard.query.adapter.out.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The Transaction 360 read model.
 *
 * <p>A deeply nested document assembled from many event types, whose shape grows as new event types
 * are added. This is the genuine fit that justifies MongoDB
 * ({@code docs/adr/0002-polyglot-persistence.md}) — and the ADR also concedes PostgreSQL JSONB would
 * serve it perfectly well.
 *
 * <p><b>Amounts are strings, never numbers.</b> BSON has no exact decimal in its default number
 * types and the JSON boundary to a browser would destroy precision anyway (ADR-0009).
 */
@Document(collection = "transaction_360")
public class Transaction360Document {

    @Id
    private String transactionId;

    private UUID correlationId;
    private String reference;
    private String amount;
    private String currency;
    private String direction;
    private String counterpartyId;
    private String status;
    private Instant occurredAt;

    /** Set on every projection write; the difference from occurredAt IS the projection lag. */
    private Instant updatedAt;

    /**
     * When the reconciliation outcome arrived, or null while the transaction is still in flight.
     *
     * <p>Distinct from {@code updatedAt}, which moves on every write. This is what separates
     * "finished" from "seen recently", and it is the population any match rate must divide by.
     */
    private Instant reconciledAt;

    private List<LifecycleNode> timeline = new ArrayList<>();

    /**
     * Highest event sequence applied.
     *
     * <p>Guards against a stale event overwriting newer state after a retry. Under the non-blocking
     * retry ladder a redelivered event can arrive AFTER later events for the same key
     * (ADR-0012), so "last write wins" would silently regress the projection.
     */
    private long lastAppliedSequence = -1;

    public Transaction360Document() {}

    public Transaction360Document(String transactionId) {
        this.transactionId = transactionId;
    }

    /** One hop in the distributed lifecycle. */
    public record LifecycleNode(
            String stage, String service, Instant occurredAt, Instant processedAt, String status, String detail) {}

    /**
     * Adds a node, keeping the timeline ordered and free of duplicates.
     *
     * <p>Idempotent by construction: replaying the same event does not append a second node. That
     * matters because the projection consumer's dedupe is the primary guard, but a projection that
     * also tolerates replay makes a rebuild from the topic safe without any dedupe at all.
     */
    public void addNode(LifecycleNode node) {
        boolean alreadyPresent = timeline.stream()
                .anyMatch(n -> n.stage().equals(node.stage()) && n.occurredAt().equals(node.occurredAt()));
        if (!alreadyPresent) {
            timeline.add(node);
            timeline.sort(Comparator.comparing(LifecycleNode::occurredAt).thenComparing(LifecycleNode::stage));
        }
    }

    public boolean isNewerThan(long sequence) {
        return sequence > lastAppliedSequence;
    }

    public void recordSequence(long sequence) {
        this.lastAppliedSequence = Math.max(this.lastAppliedSequence, sequence);
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(UUID correlationId) {
        this.correlationId = correlationId;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getCounterpartyId() {
        return counterpartyId;
    }

    public void setCounterpartyId(String counterpartyId) {
        this.counterpartyId = counterpartyId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(Instant reconciledAt) {
        this.reconciledAt = reconciledAt;
    }

    /** True once a reconciliation outcome has been projected. */
    public boolean isReconciled() {
        return reconciledAt != null;
    }

    public List<LifecycleNode> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<LifecycleNode> timeline) {
        this.timeline = timeline;
    }

    public long getLastAppliedSequence() {
        return lastAppliedSequence;
    }

    public void setLastAppliedSequence(long lastAppliedSequence) {
        this.lastAppliedSequence = lastAppliedSequence;
    }
}
