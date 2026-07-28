package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import dev.ledgerguard.reconciliation.domain.saga.SagaStatus;

/** One step ATTEMPT. Retries produce additional rows rather than mutating a counter. */
@Entity
@Table(name = "saga_step")
public class SagaStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column
    private String error;

    protected SagaStepEntity() {}

    public SagaStepEntity(UUID sagaId, int stepNumber, String stepName, int attempt, Instant now) {
        this.sagaId = sagaId;
        this.stepNumber = stepNumber;
        this.stepName = stepName;
        this.attempt = attempt;
        this.status = SagaStatus.EXECUTING;
        this.startedAt = now;
    }

    public void complete(Instant now) {
        this.status = SagaStatus.COMPLETED;
        this.finishedAt = now;
    }

    public void fail(String error, Instant now) {
        this.status = SagaStatus.FAILED;
        this.error = error;
        this.finishedAt = now;
    }

    public int stepNumber() {
        return stepNumber;
    }

    public String stepName() {
        return stepName;
    }

    public int attempt() {
        return attempt;
    }

    public SagaStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}
