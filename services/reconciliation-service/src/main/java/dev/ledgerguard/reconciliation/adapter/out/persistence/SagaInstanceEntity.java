package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import dev.ledgerguard.reconciliation.domain.saga.SagaState;

/** One workflow instance. State lives here so the Saga Control Center can query it. */
@Entity
@Table(name = "saga_instance")
public class SagaInstanceEntity {

    @Id
    private UUID id;

    @Version
    private long version;

    @Column(name = "saga_type", nullable = false)
    private String sagaType;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    protected SagaInstanceEntity() {}

    public SagaInstanceEntity(
            UUID id, String sagaType, UUID transactionId, UUID correlationId, Instant now, Instant deadline) {
        this.id = id;
        this.sagaType = sagaType;
        this.transactionId = transactionId;
        this.correlationId = correlationId;
        this.state = SagaState.STARTED;
        this.currentStep = 0;
        this.startedAt = now;
        this.updatedAt = now;
        this.deadlineAt = deadline;
    }

    /** Applies a transition, rejecting anything the allow-table forbids. */
    public void transitionTo(SagaState target, Instant now) {
        this.state = this.state.transitionTo(target);
        this.updatedAt = now;
        if (this.state.isTerminal()) {
            this.completedAt = now;
        }
    }

    public void advanceToStep(int step, Instant now) {
        this.currentStep = step;
        this.updatedAt = now;
    }

    public void recordFailure(String reason, Instant now) {
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public long version() {
        return version;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public SagaState state() {
        return state;
    }

    public int currentStep() {
        return currentStep;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String failureReason() {
        return failureReason;
    }
}
