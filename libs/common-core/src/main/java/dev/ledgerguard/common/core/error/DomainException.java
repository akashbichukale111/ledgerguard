package dev.ledgerguard.common.core.error;

import java.util.Map;
import java.util.Objects;

/**
 * Base class for errors that are part of the domain's vocabulary rather than bugs.
 *
 * <p>Carrying an {@link ErrorCode} means the HTTP layer can translate any domain failure into an
 * RFC 9457 response without a chain of {@code instanceof} checks, and without leaking class names
 * or stack traces to a caller.
 *
 * <p>{@link #details()} holds structured context — the offending value, the states involved — so
 * that a client gets something actionable instead of a sentence. Anything placed here is
 * <b>caller-visible</b>: never put PII or internal identifiers in it.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    protected DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    protected DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    protected DomainException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.details = Map.copyOf(Objects.requireNonNull(details, "details must not be null"));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
