package dev.ledgerguard.reconciliation.domain.saga;

import java.util.Objects;

/**
 * One step of the reconciliation workflow, declared as data.
 *
 * <p>Each step names its forward action and its compensating action. Declaring compensation
 * alongside the forward step — rather than in a separate handler somewhere else — is what makes it
 * impossible to add a step and forget how to undo it.
 *
 * @param compensating whether failure of a LATER step requires this one to be undone. A read-only
 *     step has nothing to compensate, and pretending otherwise generates pointless no-op records.
 */
public record SagaStepDefinition(int number, String name, boolean compensating) {

    public SagaStepDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (number < 1) {
            throw new IllegalArgumentException("step number must be >= 1, got " + number);
        }
    }
}
