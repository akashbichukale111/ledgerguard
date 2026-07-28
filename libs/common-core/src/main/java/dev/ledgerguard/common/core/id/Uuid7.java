package dev.ledgerguard.common.core.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RFC 9562 UUID version 7 — a time-ordered UUID.
 *
 * <p>Java 21 has no built-in v7 generator, so this is ours. That makes correctness our problem: a
 * subtly wrong implementation silently destroys the ordering property that is the entire
 * justification for choosing v7 over v4 ({@code docs/adr/0010-uuidv7-identifiers.md}). It is
 * therefore tested for bit layout, monotonicity, and uniqueness under concurrency.
 *
 * <p>Layout (128 bits):
 *
 * <pre>
 *   48 bits  unix_ts_ms      big-endian milliseconds since the epoch
 *    4 bits  version         0b0111
 *   12 bits  rand_a          monotonic counter within the same millisecond
 *    2 bits  variant         0b10
 *   62 bits  rand_b          random
 * </pre>
 *
 * <p><b>Monotonicity within a millisecond.</b> A plain implementation randomises {@code rand_a},
 * so two IDs minted in the same millisecond can sort in either order. This implementation uses
 * {@code rand_a} as a counter (RFC 9562 §6.2, "Replace Leftmost Random Bits with Increased Clock
 * Precision" method 2), giving a total order for up to 4096 identifiers per millisecond. Beyond
 * that the generator waits for the next millisecond rather than wrapping the counter, because
 * wrapping would reorder IDs — the one thing this class exists to prevent.
 *
 * <p><b>Not for secrets.</b> The timestamp is public and the random component is smaller than
 * v4's. Session tokens and anything whose security depends on unguessability must use a CSPRNG
 * directly, not this class.
 *
 * <p>Thread-safe.
 */
public final class Uuid7 {

    private static final int VERSION = 7;
    private static final int VARIANT = 2;
    private static final int MAX_COUNTER = 0xFFF; // 12 bits of rand_a

    private final Clock clock;
    private final SecureRandom random;

    /**
     * Packs {@code lastMillis} and {@code counter} into one long so both advance in a single CAS.
     * Holding them in two fields would allow a thread to observe a new timestamp with a stale
     * counter and mint a duplicate.
     *
     * <p>Layout: {@code (millis << 12) | counter}.
     */
    private final AtomicLong state = new AtomicLong(0);

    public Uuid7(Clock clock) {
        this(clock, new SecureRandom());
    }

    Uuid7(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** Generates the next identifier. Monotonically non-decreasing across calls. */
    public UUID next() {
        long packed = nextPackedState();
        long millis = packed >>> 12;
        long counter = packed & MAX_COUNTER;

        long msb = (millis & 0xFFFFFFFFFFFFL) << 16 // 48 bits of timestamp
                | ((long) VERSION) << 12 // 4 bits of version
                | counter; // 12 bits of rand_a, used as a counter

        long lsb = random.nextLong();
        lsb &= 0x3FFFFFFFFFFFFFFFL; // clear the top 2 bits
        lsb |= ((long) VARIANT) << 62; // set variant to 0b10

        return new UUID(msb, lsb);
    }

    private long nextPackedState() {
        while (true) {
            long now = clock.millis();
            long current = state.get();
            long currentMillis = current >>> 12;
            long currentCounter = current & MAX_COUNTER;

            long candidate;
            if (now > currentMillis) {
                candidate = (now << 12);
            } else if (currentCounter < MAX_COUNTER) {
                // Same millisecond (or the clock went backwards): advance the counter so ordering
                // is preserved. Reusing currentMillis rather than `now` also makes a backwards
                // clock step non-fatal — IDs stay monotonic, they just carry the older timestamp.
                candidate = (currentMillis << 12) | (currentCounter + 1);
            } else {
                // 4096 IDs in one millisecond. Wait rather than wrap: wrapping would reorder.
                Thread.onSpinWait();
                continue;
            }

            if (state.compareAndSet(current, candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Extracts the creation timestamp from a v7 UUID.
     *
     * <p>Useful for debugging and for the audit trail. Note that this is exactly the information
     * v7 deliberately leaks — see the ADR's privacy trade-off.
     *
     * @throws IllegalArgumentException if the UUID is not version 7
     */
    public static Instant timestampOf(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        if (uuid.version() != VERSION) {
            throw new IllegalArgumentException("not a UUIDv7 (version " + uuid.version() + "): " + uuid);
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }
}
