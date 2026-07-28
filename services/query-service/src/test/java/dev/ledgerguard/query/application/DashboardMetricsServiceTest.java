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
    void sampleSizeReportsWhatTheFiguresWereComputedFrom() {
        // The console prints this so a sampled figure is not read as an exhaustive one.
        sample(List.of(doc("a", NOW, NOW), doc("b", NOW, NOW)));
        when(transactions.count()).thenReturn(50_000L);

        var metrics = service.current();

        assertThat(metrics.sampleSize()).isEqualTo(2);
        assertThat(metrics.transactionCount()).isEqualTo(50_000L);
    }
}
