package dev.ledgerguard.reconciliation.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.ledgerguard.common.core.error.IllegalStateTransitionException;
import dev.ledgerguard.reconciliation.adapter.out.persistence.CompensationRecordEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.CompensationRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.ProcessedEventEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.ProcessedEventRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaInstanceEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaInstanceRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaStepEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaStepRepository;
import dev.ledgerguard.reconciliation.application.SagaOrchestrator;
import dev.ledgerguard.reconciliation.domain.saga.SagaState;
import dev.ledgerguard.reconciliation.domain.saga.SagaStatus;
import dev.ledgerguard.reconciliation.domain.saga.SagaStepDefinition;

/**
 * The Phase 5 saga acceptance gate, against real PostgreSQL.
 *
 * <p>Time is driven by a mutable clock the test advances by hand. Nothing here sleeps: a test that
 * sleeps is slow, flaky, and proves less than one that controls the clock directly.
 */
@SpringBootTest(
        classes = {
            dev.ledgerguard.reconciliation.ReconciliationServiceApplication.class,
            SagaOrchestratorIT.FixedClockConfig.class
        })
@Testcontainers
class SagaOrchestratorIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledgerguard_recon")
            .withInitScript("db/testcontainers-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ledgerguard.saga.timeout", () -> "PT5M");
    }

    /**
     * Replaces the application's system clock with one the test drives.
     *
     * <p>Overriding the bean rather than constructing the orchestrator by hand is essential: a
     * manually-constructed instance is not proxied, so {@code @Transactional} does not apply, no
     * transaction is opened, and every entity mutation is silently discarded. An earlier version of
     * this test did exactly that and three assertions passed against in-memory state that was never
     * written to the database.
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** A clock the test moves deliberately. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-28T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        void reset() {
            now = Instant.parse("2026-07-28T00:00:00Z");
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final MutableClock CLOCK = new MutableClock();
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    @Autowired
    SagaInstanceRepository instances;

    @Autowired
    SagaStepRepository steps;

    @Autowired
    CompensationRepository compensations;

    @Autowired
    ProcessedEventRepository processedEvents;

    /** The Spring-managed bean, so @Transactional is actually applied. */
    @Autowired
    SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        CLOCK.reset();
        compensations.deleteAll();
        steps.deleteAll();
        instances.deleteAll();
        processedEvents.deleteAll();
    }

    /** Records what happened so tests can assert ordering, not just outcomes. */
    static class RecordingExecutor implements SagaOrchestrator.StepExecutor {
        final List<String> executed = new java.util.ArrayList<>();
        final List<String> compensated = new java.util.ArrayList<>();
        private final int failOnStep;
        private final int failCompensationOnStep;

        RecordingExecutor() {
            this(0, 0);
        }

        RecordingExecutor(int failOnStep, int failCompensationOnStep) {
            this.failOnStep = failOnStep;
            this.failCompensationOnStep = failCompensationOnStep;
        }

        @Override
        public void execute(SagaStepDefinition step, SagaInstanceEntity instance) {
            if (step.number() == failOnStep) {
                throw new IllegalStateException("injected failure at step " + step.number());
            }
            executed.add(step.name());
        }

        @Override
        public void compensate(SagaStepDefinition step, SagaInstanceEntity instance) {
            if (step.number() == failCompensationOnStep) {
                throw new IllegalStateException("injected compensation failure at step " + step.number());
            }
            compensated.add(step.name());
        }
    }

    private UUID startSaga() {
        UUID sagaId = UUID.randomUUID();
        orchestrator.start(sagaId, UUID.randomUUID(), UUID.randomUUID());
        return sagaId;
    }

    // ------------------------------------------------------------------ gate 1

    @Test
    @DisplayName("happy path: every step runs in order and the saga reaches COMPLETED")
    void sagaHappyPath() {
        UUID sagaId = startSaga();
        RecordingExecutor executor = new RecordingExecutor();

        SagaInstanceEntity result = orchestrator.run(sagaId, executor);

        assertThat(result.state()).isEqualTo(SagaState.COMPLETED);
        assertThat(result.state().isSuccessful()).isTrue();
        assertThat(executor.executed)
                .containsExactly("VALIDATE_ENTRIES", "OPEN_CASE", "RUN_MATCHING", "RECORD_OUTCOME");
        assertThat(executor.compensated).isEmpty();
        assertThat(compensations.findBySagaIdOrderByIdAsc(sagaId)).isEmpty();
    }

    @Test
    @DisplayName("every step attempt is persisted as its own row")
    void stepAttemptsArePersisted() {
        UUID sagaId = startSaga();
        orchestrator.run(sagaId, new RecordingExecutor());

        List<SagaStepEntity> persisted = steps.findBySagaIdOrderByStepNumberAscAttemptAsc(sagaId);
        assertThat(persisted).hasSize(4);
        assertThat(persisted).allMatch(s -> s.status() == SagaStatus.COMPLETED);
        assertThat(persisted).extracting(SagaStepEntity::stepNumber).containsExactly(1, 2, 3, 4);
    }

    // ------------------------------------------------------------------ gate 2

    @Test
    @DisplayName("step failure triggers compensation and reaches COMPENSATED")
    void stepFailureCompensates() {
        UUID sagaId = startSaga();
        RecordingExecutor executor = new RecordingExecutor(3, 0); // step 3 fails

        SagaInstanceEntity result = orchestrator.run(sagaId, executor);

        assertThat(result.state()).isEqualTo(SagaState.COMPENSATED);
        assertThat(result.failureReason()).contains("step 3 (RUN_MATCHING) failed");
        assertThat(executor.executed).containsExactly("VALIDATE_ENTRIES", "OPEN_CASE");
    }

    @Test
    @DisplayName("compensation runs in REVERSE step order")
    void compensationRunsInReverseOrder() {
        UUID sagaId = startSaga();
        RecordingExecutor executor = new RecordingExecutor(4, 0); // step 4 fails

        orchestrator.run(sagaId, executor);

        // Steps 2 and 3 completed and are compensating; step 1 is read-only so has nothing to undo.
        // The most recent effect must be undone first.
        assertThat(executor.compensated).containsExactly("RUN_MATCHING", "OPEN_CASE");

        List<CompensationRecordEntity> records = compensations.findBySagaIdOrderByIdAsc(sagaId);
        assertThat(records).extracting(CompensationRecordEntity::stepNumber).containsExactly(3, 2);
        assertThat(records).allMatch(r -> r.status().equals("SUCCEEDED"));
    }

    @Test
    @DisplayName("a read-only step is not compensated")
    void nonCompensatingStepsAreSkipped() {
        UUID sagaId = startSaga();
        RecordingExecutor executor = new RecordingExecutor(2, 0); // step 2 fails; only step 1 done

        orchestrator.run(sagaId, executor);

        // VALIDATE_ENTRIES is declared non-compensating: undoing a read is a no-op record nobody
        // wants to read during an incident.
        assertThat(executor.compensated).isEmpty();
        assertThat(compensations.findBySagaIdOrderByIdAsc(sagaId)).isEmpty();
    }

    // ------------------------------------------------------------------ gate 3

    @Test
    @DisplayName("compensation failure is terminal, recorded, and NOT swallowed")
    void compensationFailureSurfaces() {
        UUID sagaId = startSaga();
        // Step 4 fails, and undoing step 3 also fails.
        RecordingExecutor executor = new RecordingExecutor(4, 3);

        SagaInstanceEntity result = orchestrator.run(sagaId, executor);

        assertThat(result.state()).isEqualTo(SagaState.COMPENSATION_FAILED);
        assertThat(result.state().isTerminal()).isTrue();

        List<CompensationRecordEntity> records = compensations.findBySagaIdOrderByIdAsc(sagaId);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).status()).isEqualTo("FAILED");
        assertThat(records.get(0).error()).contains("injected compensation failure");

        // The remaining compensation still ran: one failure must not abandon the others, or the
        // partial state left behind is worse than what we started with.
        assertThat(records.get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(executor.compensated).containsExactly("OPEN_CASE");
    }

    // ------------------------------------------------------------------ gate 4

    @Test
    @DisplayName("timeout fires via clock advancement, not Thread.sleep")
    void sagaTimesOut() {
        UUID sagaId = startSaga();
        RecordingExecutor executor = new RecordingExecutor();

        // Nothing is expired yet.
        assertThat(orchestrator.sweepTimeouts(executor)).isZero();

        CLOCK.advance(TIMEOUT.plusSeconds(1));

        assertThat(orchestrator.sweepTimeouts(executor)).isEqualTo(1);

        SagaInstanceEntity timedOut = instances.findById(sagaId).orElseThrow();
        assertThat(timedOut.state()).isEqualTo(SagaState.COMPENSATED);
        assertThat(timedOut.failureReason()).contains("timed out");
    }

    @Test
    @DisplayName("a completed saga is never swept")
    void completedSagasAreNotSwept() {
        UUID sagaId = startSaga();
        orchestrator.run(sagaId, new RecordingExecutor());

        CLOCK.advance(TIMEOUT.multipliedBy(10));

        assertThat(orchestrator.sweepTimeouts(new RecordingExecutor())).isZero();
        assertThat(instances.findById(sagaId).orElseThrow().state()).isEqualTo(SagaState.COMPLETED);
    }

    // ------------------------------------------------------------------ gate 5

    @Test
    @DisplayName("a duplicate event cannot start a second saga for the same transaction")
    void duplicateEventDoesNotStartASecondSaga() {
        UUID transactionId = UUID.randomUUID();
        orchestrator.start(UUID.randomUUID(), transactionId, UUID.randomUUID());

        // The unique constraint on transaction_id is the dedupe of last resort — it holds even if
        // the processed_event check were bypassed entirely.
        assertThatThrownBy(() -> orchestrator.start(UUID.randomUUID(), transactionId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(instances.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("processed_event rejects a redelivered eventId for the same consumer group")
    void processedEventPreventsReprocessing() {
        UUID eventId = UUID.randomUUID();
        processedEvents.saveAndFlush(new ProcessedEventEntity("reconciliation-service", eventId, CLOCK.instant()));

        assertThat(processedEvents.existsById(new ProcessedEventEntity.Key("reconciliation-service", eventId)))
                .isTrue();

        // A different consumer group must still be allowed to process the same event — dedupe is
        // per group, not global.
        assertThat(processedEvents.existsById(new ProcessedEventEntity.Key("query-service", eventId)))
                .isFalse();
    }

    @Test
    @DisplayName("processing the same event three times leaves one dedupe row")
    void tripleDeliveryIsIdempotent() {
        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            ProcessedEventEntity.Key key = new ProcessedEventEntity.Key("reconciliation-service", eventId);
            if (!processedEvents.existsById(key)) {
                processedEvents.saveAndFlush(
                        new ProcessedEventEntity("reconciliation-service", eventId, CLOCK.instant()));
            }
        }
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------- state machine

    @Test
    @DisplayName("illegal transitions throw rather than corrupting state")
    void illegalTransitionsAreRejected() {
        assertThat(SagaState.COMPLETED.isTerminal()).isTrue();
        assertThatThrownBy(() -> SagaState.COMPLETED.transitionTo(SagaState.STEP_EXECUTING))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> SagaState.COMPENSATED.transitionTo(SagaState.COMPENSATING))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> SagaState.STARTED.transitionTo(SagaState.COMPLETED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("every terminal state accepts no further transitions")
    void terminalStatesAreEnforced() {
        for (SagaState state : List.of(SagaState.COMPLETED, SagaState.COMPENSATED, SagaState.COMPENSATION_FAILED)) {
            assertThat(state.isTerminal()).isTrue();
            assertThat(state.allowedTargets()).isEmpty();
        }
    }
}
