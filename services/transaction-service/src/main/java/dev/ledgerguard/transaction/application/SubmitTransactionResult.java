package dev.ledgerguard.transaction.application;

import java.util.UUID;

/**
 * Outcome of a submit command.
 *
 * <p>{@code replay} is not an error: the caller retried safely and gets the original response, with
 * {@code Idempotent-Replay: true} so it can tell the difference if it cares.
 */
public record SubmitTransactionResult(UUID transactionId, int status, String body, boolean replay) {

    public static SubmitTransactionResult accepted(UUID transactionId, String body) {
        return new SubmitTransactionResult(transactionId, 202, body, false);
    }

    public static SubmitTransactionResult replay(int status, String body) {
        return new SubmitTransactionResult(null, status, body, true);
    }
}
