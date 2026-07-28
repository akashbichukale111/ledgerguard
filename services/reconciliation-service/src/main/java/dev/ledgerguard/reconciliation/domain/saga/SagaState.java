package dev.ledgerguard.reconciliation.domain.saga;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import dev.ledgerguard.common.core.error.IllegalStateTransitionException;

/**
 * Saga lifecycle, with legal transitions declared as an explicit allow-table.
 *
 * <p>{@link #COMPENSATION_FAILED} is terminal, alertable, and never silently swallowed: it means
 * the system could not undo its own partial work and a human must intervene. Treating it as just
 * another failure state is how partial financial effects become permanent without anyone noticing.
 */
public enum SagaState {
    STARTED,
    STEP_EXECUTING,
    STEP_COMPLETED,
    COMPLETED,
    COMPENSATION_REQUIRED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED,
    TIMED_OUT;

    private static final Map<SagaState, Set<SagaState>> ALLOWED = Map.of(
            STARTED, EnumSet.of(STEP_EXECUTING, TIMED_OUT, COMPENSATION_REQUIRED),
            STEP_EXECUTING, EnumSet.of(STEP_COMPLETED, COMPENSATION_REQUIRED, TIMED_OUT),
            STEP_COMPLETED, EnumSet.of(STEP_EXECUTING, COMPLETED, COMPENSATION_REQUIRED, TIMED_OUT),
            TIMED_OUT, EnumSet.of(COMPENSATION_REQUIRED),
            COMPENSATION_REQUIRED, EnumSet.of(COMPENSATING),
            COMPENSATING, EnumSet.of(COMPENSATED, COMPENSATION_FAILED),
            COMPLETED, EnumSet.noneOf(SagaState.class),
            COMPENSATED, EnumSet.noneOf(SagaState.class),
            COMPENSATION_FAILED, EnumSet.noneOf(SagaState.class));

    public boolean canTransitionTo(SagaState target) {
        return ALLOWED.get(this).contains(target);
    }

    public SagaState transitionTo(SagaState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException("SagaInstance", this, target);
        }
        return target;
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** True when the saga finished without needing to undo anything. */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }

    public Set<SagaState> allowedTargets() {
        return Set.copyOf(ALLOWED.get(this));
    }
}
