package dev.ledgerguard.query.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;
import dev.ledgerguard.query.adapter.out.persistence.AuditEventRepository;
import dev.ledgerguard.query.adapter.out.persistence.DltMessageRepository;

/**
 * Computes the console's dashboard figures.
 *
 * <p>Every field here is derived from data this service actually holds.
 *
 * <p><b>Match rate and error rate are back.</b> They were omitted through Phase 17 because nothing
 * ever moved a transaction off {@code RECEIVED} — {@code TransactionReceived} was the only
 * contracted event, so there was no finished population to divide by, and a rate computed off an
 * empty numerator would have been a fabrication. {@code TransactionReconciled} supplies that
 * population, so the rates are now measured.
 *
 * <p>They are reported against {@code reconciledCount}, not against every transaction, and the
 * pending count is published alongside. A rate whose denominator silently includes in-flight work
 * reads as a failure rate when it is really a backlog — the two need to stay distinguishable.
 *
 * <p><b>Consumer lag remains absent.</b> It needs a Kafka {@code AdminClient} querying group
 * offsets, which this service does not have, so the number could only be invented.
 */
@Service
public class DashboardMetricsService {

    /**
     * How many recent transactions the lag and throughput figures sample.
     *
     * <p>Bounded on purpose: these are dashboard numbers refreshed every few seconds, and a full
     * collection scan on each poll would cost more than the answer is worth.
     */
    private static final int SAMPLE_SIZE = 200;

    private static final Duration THROUGHPUT_WINDOW = Duration.ofHours(1);

    private final Transaction360Repository transactions;
    private final DltMessageRepository dltMessages;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public DashboardMetricsService(
            Transaction360Repository transactions,
            DltMessageRepository dltMessages,
            AuditEventRepository auditEvents,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dltMessages = Objects.requireNonNull(dltMessages, "dltMessages");
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The dashboard payload.
     *
     * @param projectionLagMillis worst event-time-to-projection-time gap in the sample, or 0 when
     *     there is nothing to measure
     * @param dltDepth dead letters not yet replayed
     * @param transactionCount transactions in the read model
     * @param transactionsLastHour transactions whose event time falls in the last hour, within the
     *     sample — a floor, not a total, when volume exceeds the sample size
     * @param auditChainLength entries in the audit chain
     * @param reconciledCount transactions that have reached a terminal outcome — the denominator of
     *     both rates below
     * @param pendingCount transactions still in flight. Published so a low match rate caused by a
     *     backlog is distinguishable from one caused by failures
     * @param matchRate fraction of reconciled transactions the engine closed itself, in [0,1], or
     *     null when nothing has reconciled yet. Null rather than zero: "no data" and "nothing
     *     matched" are different answers and an operator must be able to tell them apart
     * @param reviewRate fraction of reconciled transactions awaiting human review, in [0,1], or null
     * @param errorRate fraction of reconciled transactions with no counterpart at all, in [0,1], or
     *     null
     * @param sampleSize how many documents the lag and window figures were computed from, so the
     *     console can say what the numbers are based on rather than implying they are exhaustive.
     *     The rates above are NOT sampled — they are exact counts over the whole collection
     */
    public record DashboardMetrics(
            long projectionLagMillis,
            long dltDepth,
            long transactionCount,
            long transactionsLastHour,
            long auditChainLength,
            long reconciledCount,
            long pendingCount,
            Double matchRate,
            Double reviewRate,
            Double errorRate,
            int sampleSize) {}

    /** Terminal statuses, mirroring {@code ReconciliationOutcome} on the producing side. */
    private static final String MATCHED = "MATCHED";

    private static final String REQUIRES_REVIEW = "REQUIRES_REVIEW";
    private static final String UNMATCHED = "UNMATCHED";

    public DashboardMetrics current() {
        List<Transaction360Document> sample =
                transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(PageRequest.of(0, SAMPLE_SIZE));

        Instant windowStart = clock.instant().minus(THROUGHPUT_WINDOW);

        long worstLagMillis = sample.stream()
                .filter(d -> d.getOccurredAt() != null && d.getUpdatedAt() != null)
                .mapToLong(d ->
                        Duration.between(d.getOccurredAt(), d.getUpdatedAt()).toMillis())
                // A clock skew between services can make this negative; a negative lag is not
                // meaningful to an operator, so it floors at zero.
                .map(millis -> Math.max(0L, millis))
                .max()
                .orElse(0L);

        long inWindow = sample.stream()
                .filter(d -> d.getOccurredAt() != null && d.getOccurredAt().isAfter(windowStart))
                .count();

        // Exact counts, not sampled: a match rate computed off the most recent 200 documents would
        // swing with arrival order and tell an operator nothing.
        long total = transactions.count();
        long reconciled = transactions.countByReconciledAtIsNotNull();

        return new DashboardMetrics(
                worstLagMillis,
                dltMessages.countByReplayedAtIsNull(),
                total,
                inWindow,
                auditEvents.count(),
                reconciled,
                Math.max(0L, total - reconciled),
                rateOf(transactions.countByStatus(MATCHED), reconciled),
                rateOf(transactions.countByStatus(REQUIRES_REVIEW), reconciled),
                rateOf(transactions.countByStatus(UNMATCHED), reconciled),
                sample.size());
    }

    /**
     * A rate, or null when there is nothing to divide by.
     *
     * <p>Null rather than 0.0 on an empty population. Zero would render as "0% matched", which reads
     * as a total failure when the truth is that nothing has finished yet.
     */
    private static Double rateOf(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
