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
 * <p>Every field here is derived from data this service actually holds. That constraint is the
 * reason the shape is narrower than the console's first draft, which asked for consumer lag, a
 * match rate and an error rate:
 *
 * <ul>
 *   <li><b>Consumer lag</b> needs a Kafka {@code AdminClient} querying group offsets. The service
 *       has no admin client, so the number could only have been invented.
 *   <li><b>Match rate</b> and <b>error rate</b> need a terminal outcome per transaction. Today the
 *       projection only ever sets status {@code RECEIVED} — {@code TransactionReceived} is the sole
 *       contracted event type — so there is no matched or failed population to divide by. Reporting
 *       "100% matched" off an empty numerator would be worse than reporting nothing.
 * </ul>
 *
 * <p>Those three are omitted rather than faked. See the phase-17 report.
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
     * @param sampleSize how many documents the lag and window figures were computed from, so the
     *     console can say what the numbers are based on rather than implying they are exhaustive
     */
    public record DashboardMetrics(
            long projectionLagMillis,
            long dltDepth,
            long transactionCount,
            long transactionsLastHour,
            long auditChainLength,
            int sampleSize) {}

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

        return new DashboardMetrics(
                worstLagMillis,
                dltMessages.countByReplayedAtIsNull(),
                transactions.count(),
                inWindow,
                auditEvents.count(),
                sample.size());
    }
}
