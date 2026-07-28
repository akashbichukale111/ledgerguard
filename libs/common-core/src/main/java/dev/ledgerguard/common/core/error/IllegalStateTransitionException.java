package dev.ledgerguard.common.core.error;

import java.util.Map;

/**
 * Thrown when a state machine is asked to make a transition its allow-table does not permit.
 *
 * <p>Every state machine in LedgerGuard declares its legal transitions as data and throws this
 * rather than silently accepting an impossible move. That matters most under the non-blocking retry
 * ladder ({@code docs/adr/0012-non-blocking-retry-topics.md}): a retried event can arrive after
 * later events for the same key, and rejecting the stale transition is what stops it corrupting
 * state.
 */
public class IllegalStateTransitionException extends DomainException {

    public IllegalStateTransitionException(String aggregateType, Object from, Object to) {
        super(
                ErrorCode.ILLEGAL_STATE_TRANSITION,
                String.format("%s cannot transition from %s to %s", aggregateType, from, to),
                Map.of("aggregateType", aggregateType, "from", from.toString(), "to", to.toString()));
    }
}
