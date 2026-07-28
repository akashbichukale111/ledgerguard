package dev.ledgerguard.query.adapter.in.rest.dto;

import java.time.Instant;
import java.util.List;

import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;

/**
 * A transaction as the console renders it.
 *
 * <p>Amount stays a string end to end. Turning it into a JSON number here would lose exactness at
 * the browser boundary, which is the whole reason the projection stores it as text (ADR-0009).
 */
public record TransactionSummary(
        String transactionId,
        String status,
        String amount,
        String currency,
        String reference,
        String direction,
        String counterpartyId,
        Instant occurredAt,
        Instant updatedAt,
        String correlationId,
        List<LifecycleStep> timeline) {

    /** One hop of the distributed lifecycle. */
    public record LifecycleStep(
            String stage, String service, Instant occurredAt, Instant processedAt, String status, String detail) {}

    public static TransactionSummary from(Transaction360Document document) {
        return new TransactionSummary(
                document.getTransactionId(),
                document.getStatus(),
                document.getAmount(),
                document.getCurrency(),
                document.getReference(),
                document.getDirection(),
                document.getCounterpartyId(),
                document.getOccurredAt(),
                document.getUpdatedAt(),
                document.getCorrelationId() == null
                        ? null
                        : document.getCorrelationId().toString(),
                document.getTimeline().stream()
                        .map(n -> new LifecycleStep(
                                n.stage(), n.service(), n.occurredAt(), n.processedAt(), n.status(), n.detail()))
                        .toList());
    }

    /** The list view does not need the timeline; omitting it keeps search responses small. */
    public static TransactionSummary withoutTimeline(Transaction360Document document) {
        TransactionSummary full = from(document);
        return new TransactionSummary(
                full.transactionId(),
                full.status(),
                full.amount(),
                full.currency(),
                full.reference(),
                full.direction(),
                full.counterpartyId(),
                full.occurredAt(),
                full.updatedAt(),
                full.correlationId(),
                List.of());
    }
}
