package dev.ledgerguard.transaction.application;

import dev.ledgerguard.common.core.error.DomainException;
import dev.ledgerguard.common.core.error.ErrorCode;

/**
 * Raised when an idempotency key is reused incompatibly.
 *
 * <p>Two distinct cases, both a 409:
 *
 * <ul>
 *   <li><b>Same key, different body.</b> Returning the stored response would tell the caller a
 *       different request succeeded — strictly worse than failing.
 *   <li><b>Still in flight.</b> The first request has not completed, so there is no response to
 *       replay yet. The caller should retry after a delay.
 * </ul>
 */
public class IdempotencyConflictException extends DomainException {
    public IdempotencyConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
