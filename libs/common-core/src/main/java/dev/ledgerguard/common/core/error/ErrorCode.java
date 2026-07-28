package dev.ledgerguard.common.core.error;

/**
 * Stable, machine-readable error codes.
 *
 * <p>These appear in RFC 9457 Problem Details responses and in structured logs, and clients are
 * expected to branch on them. That makes them <b>API surface</b>: a code may be added, but an
 * existing code's meaning must never change, and renaming one is a breaking change.
 *
 * <p>The catalogue is intentionally an enum rather than free-form strings so that a typo fails at
 * compile time and so the full set is enumerable for documentation.
 */
public enum ErrorCode {

    // --- validation ---------------------------------------------------------
    VALIDATION_FAILED("Request failed validation"),
    UNSUPPORTED_CURRENCY("Currency is not supported"),
    CURRENCY_MISMATCH("Operation attempted across two currencies"),
    AMOUNT_PRECISION_EXCEEDED("Amount carries more precision than the currency permits"),
    LEDGER_UNBALANCED("Debits and credits do not balance"),

    // --- idempotency --------------------------------------------------------
    IDEMPOTENCY_KEY_REQUIRED("Idempotency-Key header is required for this operation"),
    IDEMPOTENCY_KEY_REUSED("Idempotency key reused with a different request body"),
    IDEMPOTENT_REQUEST_IN_FLIGHT("A request with this idempotency key is still in flight"),

    // --- concurrency and state ---------------------------------------------
    CONCURRENT_MODIFICATION("The aggregate was modified concurrently"),
    ILLEGAL_STATE_TRANSITION("The requested transition is not legal from the current state"),

    // --- authorization ------------------------------------------------------
    UNAUTHENTICATED("Authentication is required"),
    FORBIDDEN("The authenticated principal may not perform this action"),
    WRITE_OFF_LIMIT_EXCEEDED("Write-off amount exceeds the cap for this role"),
    JUSTIFICATION_REQUIRED("This action requires a written justification"),

    // --- resources ----------------------------------------------------------
    NOT_FOUND("The requested resource does not exist"),

    // --- audit --------------------------------------------------------------
    AUDIT_CHAIN_BROKEN("Audit hash chain verification failed"),

    // --- infrastructure -----------------------------------------------------
    DEPENDENCY_UNAVAILABLE("A required downstream dependency is unavailable"),
    INTERNAL_ERROR("An unexpected error occurred");

    private final String description;

    ErrorCode(String description) {
        this.description = description;
    }

    /** Human-readable summary. Suitable for the Problem Details {@code title}. */
    public String description() {
        return description;
    }
}
