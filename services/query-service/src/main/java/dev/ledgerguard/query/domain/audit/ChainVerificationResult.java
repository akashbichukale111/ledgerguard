package dev.ledgerguard.query.domain.audit;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of walking the audit chain.
 *
 * <p>Reports the <b>first</b> index at which the chain breaks, not merely that something is wrong.
 * "The audit log is corrupt" is not actionable; "record 4,182 no longer hashes to the value record
 * 4,183 expects" tells an investigator exactly where to look and bounds what is still trustworthy.
 */
public record ChainVerificationResult(boolean intact, long recordsVerified, Long firstBrokenIndex, String detail) {

    public ChainVerificationResult {
        Objects.requireNonNull(detail, "detail must not be null");
        if (intact && firstBrokenIndex != null) {
            throw new IllegalArgumentException("an intact chain cannot name a broken index");
        }
        if (!intact && firstBrokenIndex == null) {
            throw new IllegalArgumentException("a broken chain must name the index where it broke");
        }
    }

    public static ChainVerificationResult intact(long recordsVerified) {
        return new ChainVerificationResult(
                true, recordsVerified, null, "chain intact across " + recordsVerified + " records");
    }

    public static ChainVerificationResult broken(long recordsVerified, long index, String reason) {
        return new ChainVerificationResult(
                false, recordsVerified, index, "chain broken at index " + index + ": " + reason);
    }

    public Optional<Long> brokenAt() {
        return Optional.ofNullable(firstBrokenIndex);
    }
}
