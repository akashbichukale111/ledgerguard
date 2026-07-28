package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One compensation execution and its outcome. A failed compensation is never silently dropped. */
@Entity
@Table(name = "compensation_record")
public class CompensationRecordEntity {

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
    private String status;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column
    private String error;

    protected CompensationRecordEntity() {}

    public static CompensationRecordEntity succeeded(UUID sagaId, int stepNumber, String stepName, Instant now) {
        CompensationRecordEntity record = new CompensationRecordEntity();
        record.sagaId = sagaId;
        record.stepNumber = stepNumber;
        record.stepName = stepName;
        record.status = "SUCCEEDED";
        record.executedAt = now;
        return record;
    }

    public static CompensationRecordEntity failed(
            UUID sagaId, int stepNumber, String stepName, String error, Instant now) {
        CompensationRecordEntity record = succeeded(sagaId, stepNumber, stepName, now);
        record.status = "FAILED";
        record.error = error;
        return record;
    }

    public int stepNumber() {
        return stepNumber;
    }

    public String stepName() {
        return stepName;
    }

    public String status() {
        return status;
    }

    public String error() {
        return error;
    }
}
