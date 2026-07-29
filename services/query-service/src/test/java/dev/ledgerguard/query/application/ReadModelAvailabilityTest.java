package dev.ledgerguard.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.mongodb.MongoSocketOpenException;
import com.mongodb.ServerAddress;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;
import dev.ledgerguard.query.adapter.out.persistence.AuditEventRepository;
import dev.ledgerguard.query.adapter.out.persistence.DltMessageRepository;

/**
 * The hosted demo runs without a guaranteed read model, and the requirement is that it degrades
 * rather than crashes. These pin that behaviour, and pin that it degrades <em>honestly</em>.
 */
class ReadModelAvailabilityTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final String REAL_URI = "mongodb://localhost:27017/ledgerguard_read";
    private static final String PLACEHOLDER_URI = "mongodb://read-model-not-configured:27017/ledgerguard_read";

    @Nested
    class WhenDecidingIfItIsConfigured {

        @Test
        void aSuppliedUriWithTheSwitchOnCounts() {
            assertThat(new ReadModelAvailability(true, REAL_URI).isConfigured()).isTrue();
        }

        @Test
        void theSwitchOffWinsEvenWithARealUri() {
            assertThat(new ReadModelAvailability(false, REAL_URI).isConfigured())
                    .isFalse();
        }

        @Test
        void aBlankUriCounts() {
            assertThat(new ReadModelAvailability(true, "").isConfigured()).isFalse();
        }

        @Test
        void thePlaceholderHostIsTreatedAsUnconfigured() {
            // application-cloud.yml supplies this when MONGO_URI is unset. Treating it as a real URI
            // would leave every request waiting on a DNS lookup for a host that does not exist.
            assertThat(new ReadModelAvailability(true, PLACEHOLDER_URI).isConfigured())
                    .isFalse();
        }
    }

    @Nested
    class WhenRunningAQuery {

        @Test
        void anUnconfiguredReadModelIsNotEvenCalled() {
            var availability = new ReadModelAvailability(true, PLACEHOLDER_URI);
            var neverRun = new boolean[] {false};

            String answer = availability.query(
                    () -> {
                        neverRun[0] = true;
                        return "live";
                    },
                    "degraded");

            assertThat(answer).isEqualTo("degraded");
            assertThat(neverRun[0])
                    .as("must not attempt a call it knows will fail")
                    .isFalse();
        }

        @Test
        void aSpringDataFailureDegradesInsteadOfPropagating() {
            var availability = new ReadModelAvailability(true, REAL_URI);

            String answer = availability.query(
                    () -> {
                        throw new DataAccessResourceFailureException("connection refused");
                    },
                    "degraded");

            assertThat(answer).isEqualTo("degraded");
        }

        @Test
        void aRawDriverExceptionDegradesToo() {
            // Not every failure is translated by Spring — a socket-open failure during cluster
            // discovery arrives as a raw MongoException, which is why the catch names both.
            var availability = new ReadModelAvailability(true, REAL_URI);

            String answer = availability.query(
                    () -> {
                        throw new MongoSocketOpenException("no route to host", new ServerAddress("nope", 27017));
                    },
                    "degraded");

            assertThat(answer).isEqualTo("degraded");
        }

        @Test
        void aProgrammingErrorIsNotSwallowed() {
            // Degradation must cover infrastructure being absent, not bugs. Catching everything here
            // would turn a NullPointerException into a silently empty dashboard.
            var availability = new ReadModelAvailability(true, REAL_URI);

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> availability.query(
                            () -> {
                                throw new IllegalStateException("a real bug");
                            },
                            "degraded")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class TheDashboardWhenDegraded {

        private DashboardMetricsService serviceWith(ReadModelAvailability availability, Transaction360Repository txns) {
            var dlt = mock(DltMessageRepository.class);
            var audit = mock(AuditEventRepository.class);
            when(dlt.countByReplayedAtIsNull()).thenReturn(4L);
            when(audit.count()).thenReturn(11L);
            return new DashboardMetricsService(txns, dlt, audit, Clock.fixed(NOW, ZoneOffset.UTC), availability);
        }

        @Test
        void stillAnswersInsteadOfThrowing() {
            var txns = mock(Transaction360Repository.class);
            var metrics = serviceWith(new ReadModelAvailability(false, REAL_URI), txns)
                    .current();

            assertThat(metrics.readModelAvailable()).isFalse();
            assertThat(metrics.transactionCount()).isZero();
            verify(txns, never()).count();
        }

        @Test
        void reportsRatesAsNullNotZero() {
            // 0.0 renders as "0% matched", which tells an operator the system is failing. The truth
            // is that we cannot see the data at all, and null is what the console renders as an
            // explicit empty state.
            var metrics = serviceWith(new ReadModelAvailability(false, REAL_URI), mock(Transaction360Repository.class))
                    .current();

            assertThat(metrics.matchRate()).isNull();
            assertThat(metrics.reviewRate()).isNull();
            assertThat(metrics.errorRate()).isNull();
        }

        @Test
        void stillReportsThePostgresBackedFigures() {
            // dltDepth and auditChainLength come from Postgres, which is present. Zeroing them
            // alongside the Mongo figures would hide a real dead-letter backlog.
            var metrics = serviceWith(new ReadModelAvailability(false, REAL_URI), mock(Transaction360Repository.class))
                    .current();

            assertThat(metrics.dltDepth()).isEqualTo(4L);
            assertThat(metrics.auditChainLength()).isEqualTo(11L);
        }

        @Test
        void anUnreachableMongoDegradesRatherThanFailingTheLandingPage() {
            var txns = mock(Transaction360Repository.class);
            when(txns.findAllByOrderByOccurredAtDescTransactionIdDesc(any()))
                    .thenThrow(new DataAccessResourceFailureException("timed out"));

            var metrics =
                    serviceWith(new ReadModelAvailability(true, REAL_URI), txns).current();

            assertThat(metrics.readModelAvailable()).isFalse();
            assertThat(metrics.dltDepth()).isEqualTo(4L);
        }
    }
}
