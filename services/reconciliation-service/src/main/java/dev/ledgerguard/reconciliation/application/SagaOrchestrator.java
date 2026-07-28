package dev.ledgerguard.reconciliation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.ledgerguard.reconciliation.adapter.out.persistence.CompensationRecordEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.CompensationRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaInstanceEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaInstanceRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaStepEntity;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaStepRepository;
import dev.ledgerguard.reconciliation.domain.saga.SagaState;
import dev.ledgerguard.reconciliation.domain.saga.SagaStepDefinition;

/**
 * The hand-rolled saga orchestrator.
 *
 * <p>Reasoning for building this rather than adopting Temporal is in
 * {@code docs/adr/0004-orchestrated-saga-hand-rolled.md}, including an honest statement that
 * Temporal is very likely the right production choice.
 *
 * <p>Three properties this implementation must uphold, each covered by a test rather than assumed:
 *
 * <ol>
 *   <li><b>State is persisted with the step's own effect.</b> A saga whose state is written
 *       separately from its work can lose track of what it did.
 *   <li><b>Compensation runs in reverse order.</b> Step 3's effects are undone before step 2's.
 *       This holds because the code says so, not because a framework guarantees it — which is
 *       exactly why it is tested.
 *   <li><b>Compensation failure is terminal and loud.</b> {@code COMPENSATION_FAILED} is never
 *       swallowed.
 * </ol>
 */
@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);
    private static final String SAGA_TYPE = "RECONCILIATION";

    /**
     * The workflow, declared as data.
     *
     * <p>Step 1 is read-only, so it has nothing to compensate. Steps 2 and 3 have effects that must
     * be undone. Declaring this alongside the step makes it impossible to add a step and forget how
     * to reverse it.
     */
    static final List<SagaStepDefinition> STEPS = List.of(
            new SagaStepDefinition(1, "VALIDATE_ENTRIES", false),
            new SagaStepDefinition(2, "OPEN_CASE", true),
            new SagaStepDefinition(3, "RUN_MATCHING", true),
            new SagaStepDefinition(4, "RECORD_OUTCOME", true));

    private final SagaInstanceRepository instances;
    private final SagaStepRepository steps;
    private final CompensationRepository compensations;
    private final Clock clock;
    private final Duration timeout;

    public SagaOrchestrator(
            SagaInstanceRepository instances,
            SagaStepRepository steps,
            CompensationRepository compensations,
            Clock clock,
            Duration timeout) {
        this.instances = instances;
        this.steps = steps;
        this.compensations = compensations;
        this.clock = clock;
        this.timeout = timeout;
    }

    /** Starts a workflow. The unique constraint on {@code transaction_id} rejects a second one. */
    @Transactional
    public SagaInstanceEntity start(UUID sagaId, UUID transactionId, UUID correlationId) {
        Instant now = clock.instant();
        SagaInstanceEntity instance =
                new SagaInstanceEntity(sagaId, SAGA_TYPE, transactionId, correlationId, now, now.plus(timeout));
        return instances.save(instance);
    }

    /**
     * Runs the workflow to completion, compensating on failure.
     *
     * <p>{@code stepExecutor} performs the real side effect for each step. Injecting it keeps the
     * orchestration logic testable without a matching engine, a database of entries, or Kafka —
     * the failure-injection tests below supply an executor that throws on a chosen step.
     */
    @Transactional
    public SagaInstanceEntity run(UUID sagaId, StepExecutor stepExecutor) {
        SagaInstanceEntity instance =
                instances.findById(sagaId).orElseThrow(() -> new IllegalArgumentException("no saga " + sagaId));

        for (SagaStepDefinition step : STEPS) {
            Instant now = clock.instant();

            instance.transitionTo(SagaState.STEP_EXECUTING, now);
            instance.advanceToStep(step.number(), now);
            SagaStepEntity attempt = steps.save(new SagaStepEntity(sagaId, step.number(), step.name(), 1, now));

            try {
                stepExecutor.execute(step, instance);
                attempt.complete(clock.instant());
                instance.transitionTo(SagaState.STEP_COMPLETED, clock.instant());
            } catch (RuntimeException e) {
                attempt.fail(e.toString(), clock.instant());
                log.warn("saga {} step {} failed: {}", sagaId, step.name(), e.toString());
                instance.recordFailure(
                        "step %d (%s) failed: %s".formatted(step.number(), step.name(), e.getMessage()),
                        clock.instant());
                instance.transitionTo(SagaState.COMPENSATION_REQUIRED, clock.instant());
                return compensate(instance, step.number(), stepExecutor);
            }
        }

        instance.transitionTo(SagaState.COMPLETED, clock.instant());
        log.info("saga {} completed", sagaId);
        return instance;
    }

    /**
     * Undoes completed steps in <b>reverse</b> order.
     *
     * @param failedStepNumber the step that failed; it did not complete, so it is not compensated
     */
    @Transactional
    public SagaInstanceEntity compensate(SagaInstanceEntity instance, int failedStepNumber, StepExecutor stepExecutor) {

        instance.transitionTo(SagaState.COMPENSATING, clock.instant());

        List<SagaStepDefinition> toUndo = new ArrayList<>();
        for (SagaStepDefinition step : STEPS) {
            if (step.number() < failedStepNumber && step.compensating()) {
                toUndo.add(step);
            }
        }
        // Reverse order: the most recent effect is undone first.
        java.util.Collections.reverse(toUndo);

        boolean allSucceeded = true;
        for (SagaStepDefinition step : toUndo) {
            try {
                stepExecutor.compensate(step, instance);
                compensations.save(
                        CompensationRecordEntity.succeeded(instance.id(), step.number(), step.name(), clock.instant()));
            } catch (RuntimeException e) {
                // Keep going: a later compensation failing must not prevent earlier ones from
                // running, or the partial state left behind is even worse.
                allSucceeded = false;
                compensations.save(CompensationRecordEntity.failed(
                        instance.id(), step.number(), step.name(), e.toString(), clock.instant()));
                log.error(
                        "saga {} COMPENSATION FAILED at step {} — manual intervention required",
                        instance.id(),
                        step.name());
            }
        }

        instance.transitionTo(allSucceeded ? SagaState.COMPENSATED : SagaState.COMPENSATION_FAILED, clock.instant());
        return instance;
    }

    /**
     * Drives timed-out instances into compensation.
     *
     * <p>Reads time from the injected {@link Clock}, so tests advance a fixed clock rather than
     * sleeping. A test that sleeps is slow, flaky, and proves less.
     *
     * <p><b>Stated limitation:</b> timeouts are only as durable as this sweeper actually running.
     * If it stops, timeouts silently never fire — the symptom is an absence, which is the hardest
     * kind to notice. Its liveness is therefore itself monitored (ADR-0004).
     *
     * @return the number of instances timed out
     */
    @Transactional
    public int sweepTimeouts(StepExecutor stepExecutor) {
        Instant now = clock.instant();
        List<SagaInstanceEntity> expired = instances.findExpired(now);

        for (SagaInstanceEntity instance : expired) {
            log.warn("saga {} timed out at {}", instance.id(), now);
            instance.recordFailure("timed out after " + timeout, now);
            instance.transitionTo(SagaState.TIMED_OUT, now);
            instance.transitionTo(SagaState.COMPENSATION_REQUIRED, now);
            compensate(instance, instance.currentStep() + 1, stepExecutor);
        }
        return expired.size();
    }

    /** Performs a step's real effect, and undoes it. */
    public interface StepExecutor {
        void execute(SagaStepDefinition step, SagaInstanceEntity instance);

        /**
         * Undoes a step. Must be <b>idempotent</b>: compensation runs under the same at-least-once
         * delivery as everything else, so running it twice must be harmless.
         */
        void compensate(SagaStepDefinition step, SagaInstanceEntity instance);
    }
}
