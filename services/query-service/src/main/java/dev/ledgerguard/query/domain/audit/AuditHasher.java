package dev.ledgerguard.query.domain.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonicalisation and hashing for the tamper-evident audit chain.
 *
 * <p><b>Determinism is the whole mechanism.</b> If canonicalisation produced different bytes for the
 * same logical record — because a map iterated differently, a timestamp formatted differently, or a
 * null rendered inconsistently — verification would report false breaks and the chain would be
 * worthless. Everything here is explicitly ordered and explicitly null-handled, and
 * {@code AuditHasherTest} asserts that directly rather than trusting it.
 *
 * <p><b>What this does NOT provide.</b> The chain is <b>tamper-evident, not tamper-proof</b>. An
 * attacker with write access to the whole table can recompute every hash from the point of
 * modification forward and pass verification. What the chain buys is that undetected tampering
 * requires rewriting all subsequent records instead of editing one row. See
 * {@code docs/adr/0011-tamper-evident-audit-chain.md}.
 */
public final class AuditHasher {

    /**
     * Canonicalisation format version.
     *
     * <p>Changing how records are serialised invalidates every historical hash, so the format is
     * versioned rather than assumed stable forever. Each record stores the version it was hashed
     * under, so a future format change can verify old records under the old rules.
     */
    public static final short FORMAT_VERSION = 1;

    /**
     * Field separator: ASCII Unit Separator (0x1F).
     *
     * <p>Chosen because it cannot appear in any field value. A printable separator such as
     * {@code '|'} would allow a crafted field to imitate a field boundary, so two different records
     * could canonicalise to the same bytes and one could be substituted for the other undetected.
     */
    private static final char SEP = '\u001F';

    /** Marker for an absent value, distinct from an empty string. */
    private static final String NULL_MARKER = "\u0000";

    private AuditHasher() {}

    /**
     * Renders a record to its canonical form.
     *
     * <p>Fields appear in a fixed order. Nulls become a distinct marker rather than the text
     * {@code "null"}, so a record whose action is literally the string "null" cannot collide with
     * one whose action is absent.
     */
    public static String canonicalise(AuditRecord record) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(FORMAT_VERSION).append(SEP);
        sb.append(record.chainIndex()).append(SEP);
        appendUuid(sb, record.eventId());
        appendInstant(sb, record.occurredAt());
        appendUuid(sb, record.correlationId());
        appendUuid(sb, record.causationId());
        appendString(sb, record.actorSubject());
        appendString(sb, record.actorRole());
        appendString(sb, record.actorSource());
        appendString(sb, record.service());
        appendString(sb, record.action());
        appendString(sb, record.aggregateType());
        appendUuid(sb, record.aggregateId());
        appendString(sb, record.outcome());
        appendString(sb, record.beforeState());
        appendString(sb, record.afterState());
        return sb.toString();
    }

    /**
     * {@code SHA-256( canonical(record) || previousHash )}.
     *
     * <p>Folding the previous hash into the digest is what makes modifying record N detectable at
     * record N+1: N's hash changes and no longer matches the {@code previousHash} that N+1 stores.
     */
    public static String hash(AuditRecord record) {
        String previous = record.previousHash() == null ? "" : record.previousHash();
        return sha256(canonicalise(record) + SEP + previous);
    }

    private static void appendString(StringBuilder sb, String value) {
        // Absent is not the same as present-but-blank. Conflating them would let one be swapped for
        // the other without changing the hash.
        sb.append(value == null ? NULL_MARKER : value).append(SEP);
    }

    private static void appendUuid(StringBuilder sb, UUID value) {
        sb.append(value == null ? NULL_MARKER : value.toString()).append(SEP);
    }

    private static void appendInstant(StringBuilder sb, Instant value) {
        // Epoch seconds and nanos, not a formatted string: ISO rendering varies in trailing-zero
        // handling (2026-07-28T00:00:00Z vs ...00.000Z) and that variance would break the chain for
        // records that are logically identical.
        sb.append(value == null ? NULL_MARKER : value.getEpochSecond() + ":" + value.getNano())
                .append(SEP);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
