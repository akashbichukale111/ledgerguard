package dev.ledgerguard.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;
import dev.ledgerguard.query.adapter.out.persistence.AuditEventRepository;
import dev.ledgerguard.query.adapter.out.persistence.DltMessageRepository;

@DisplayName("DashboardMetricsService")
class DashboardMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private Transaction360Repository transactions;
    private DltMessageRepository dltMessages;
    private AuditEventRepository auditEvents;
    private DashboardMetricsService service;

    @BeforeEach
    void setUp() {
        transactions = mock(Transaction360Repository.class);
        dltMessages = mock(DltMessageRepository.class);
        auditEvents = mock(AuditEventRepository.class);
        service = new DashboardMetricsService(transactions, dltMessages, auditEvents, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Transaction360Document doc(String id, Instant occurredAt, Instant updatedAt) {
        var d = new Transaction360Document(id);
        d.setOccurredAt(occurredAt);
        d.setUpdatedAt(updatedAt);
        return d;
    }

    private void sample(List<Transaction360Document> docs) {
        when(transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                .thenReturn(docs);
    }

    @Test
    void lagIsTheWorstGapInTheSampleNotTheAverage() {
        // An operator needs the worst case: an average hides one badly lagging partition.
        sample(List.of(
                doc("a", NOW.minusSeconds(10), NOW.minusSeconds(9)), // 1s
                doc("b", NOW.minusSeconds(30), NOW.minusSeconds(25)), // 5s
                doc("c", NOW.minusSeconds(5), NOW.minusSeconds(5)))); // 0s

        assertThat(service.current().projectionLagMillis()).isEqualTo(5_000L);
    }

    @Test
    void aNegativeGapFromClockSkewFloorsAtZero() {
        // updatedAt before occurredAt means the two services' clocks disagree. A negative lag is
        // not meaningful on a dashboard.
        sample(List.of(doc("a", NOW, NOW.minusSeconds(3))));

        assertThat(service.current().projectionLagMillis()).isZero();
    }

    @Test
    void documentsMissingATimestampAreIgnoredRatherThanCountedAsZero() {
        sample(List.of(doc("a", null, NOW), doc("b", NOW.minusSeconds(4), NOW)));

        assertThat(service.current().projectionLagMillis()).isEqualTo(4_000L);
    }

    @Test
    void anEmptyReadModelReportsZeroLagNotAnError() {
        sample(List.of());

        var metrics = service.current();

        assertThat(metrics.projectionLagMillis()).isZero();
        assertThat(metrics.sampleSize()).isZero();
    }

    @Test
    void theLastHourCountExcludesOlderTransactions() {
        sample(List.of(
                doc("recent", NOW.minusSeconds(600), NOW),
                doc("also-recent", NOW.minusSeconds(3_000), NOW),
                doc("too-old", NOW.minusSeconds(7_200), NOW)));

        assertThat(service.current().transactionsLastHour()).isEqualTo(2L);
    }

    @Test
    void depthCountsOnlyDeadLettersNotYetReplayed() {
        sample(List.of());
        when(dltMessages.countByReplayedAtIsNull()).thenReturn(7L);

        assertThat(service.current().dltDepth()).isEqualTo(7L);
    }

    @Test
    void ratesAreNullWhenNothingHasReconciledYet() {
        // Null, not 0.0. Zero renders as "0% matched", which reads as total failure when the truth
        // is that nothing has finished — an operator must be able to tell those apart.
        sample(List.of());
        when(transactions.countByReconciledAtIsNotNull()).thenReturn(0L);

        var metrics = service.current();

        assertThat(metrics.matchRate()).isNull();
        assertThat(metrics.reviewRate()).isNull();
        assertThat(metrics.errorRate()).isNull();
    }

    @Test
    void ratesDivideByTheReconciledPopulationNotEveryTransaction() {
        // 100 transactions exist, only 40 have finished: 30 matched, 6 in review, 4 unmatched.
        // Dividing by 100 would report 30% matched and imply 70% failed, when 60 are simply still
        // in flight.
        sample(List.of());
        when(transactions.count()).thenReturn(100L);
        when(transactions.countByReconciledAtIsNotNull()).thenReturn(40L);
        when(transactions.countByStatus("MATCHED")).thenReturn(30L);
        when(transactions.countByStatus("REQUIRES_REVIEW")).thenReturn(6L);
        when(transactions.countByStatus("UNMATCHED")).thenReturn(4L);

        var metrics = service.current();

        assertThat(metrics.matchRate()).isEqualTo(0.75);
        assertThat(metrics.reviewRate()).isEqualTo(0.15);
        assertThat(metrics.errorRate()).isEqualTo(0.10);
    }

    @Test
    void pendingIsPublishedSoABacklogIsDistinguishableFromFailures() {
        sample(List.of());
        when(transactions.count()).thenReturn(100L);
        when(transactions.countByReconciledAtIsNotNull()).thenReturn(40L);

        var metrics = service.current();

        assertThat(metrics.reconciledCount()).isEqualTo(40L);
        assertThat(metrics.pendingCount()).isEqualTo(60L);
    }

    @Test
    void pendingNeverGoesNegativeIfCountsRaceEachOther() {
        // The two counts are separate queries; a write between them can make reconciled exceed the
        // total momentarily. A negative backlog on a dashboard is worse than a stale zero.
        sample(List.of());
        when(transactions.count()).thenReturn(5L);
        when(transactions.countByReconciledAtIsNotNull()).thenReturn(7L);

        assertThat(service.current().pendingCount()).isZero();
    }

    @Test
    void ratesAreExactCountsNotSampledLikeTheLagFigures() {
        // The lag sample is bounded at 200 documents. A rate computed off that window would swing
        // with arrival order, so the rates use count queries over the whole collection instead.
        sample(List.of(doc("a", NOW, NOW)));
        when(transactions.count()).thenReturn(10_000L);
        when(transactions.countByReconciledAtIsNotNull()).thenReturn(10_000L);
        when(transactions.countByStatus("MATCHED")).thenReturn(9_900L);

        var metrics = service.current();

        assertThat(metrics.sampleSize()).isEqualTo(1);
        assertThat(metrics.matchRate()).isEqualTo(0.99);
    }

    @Test
    void sampleSizeReportsWhatTheFiguresWereComputedFrom() {
        // The console prints this so a sampled figure is not read as an exhaustive one.
        sample(List.of(doc("a", NOW, NOW), doc("b", NOW, NOW)));
        when(transactions.count()).thenReturn(50_000L);

        var metrics = service.current();

        assertThat(metrics.sampleSize()).isEqualTo(2);
        assertThat(metrics.transactionCount()).isEqualTo(50_000L);
    }
}
