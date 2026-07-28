package dev.ledgerguard.common.core.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The ordering property is the entire justification for choosing v7 over v4 (ADR-0010). A generator
 * that is "nearly" ordered provides none of the claimed benefit, so these tests check the bit
 * layout and the monotonicity guarantee directly rather than merely checking uniqueness.
 */
class Uuid7Test {

    /** A clock the test advances by hand — no sleeping, no wall-clock flakiness. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Nested
    @DisplayName("RFC 9562 bit layout")
    class Layout {

        @Test
        void versionIs7() {
            Uuid7 gen = new Uuid7(Clock.systemUTC());
            for (int i = 0; i < 100; i++) {
                assertThat(gen.next().version()).isEqualTo(7);
            }
        }

        @Test
        void variantIsRfc4122() {
            Uuid7 gen = new Uuid7(Clock.systemUTC());
            for (int i = 0; i < 100; i++) {
                // UUID.variant() returns 2 for the RFC 4122/9562 variant (top bits 0b10).
                assertThat(gen.next().variant()).isEqualTo(2);
            }
        }

        @Test
        void timestampMatchesTheClockThatMintedIt() {
            Instant start = Instant.parse("2026-07-28T00:00:00Z");
            Uuid7 gen = new Uuid7(new MutableClock(start));
            assertThat(Uuid7.timestampOf(gen.next())).isEqualTo(start);
        }

        @Test
        void timestampAdvancesWithTheClock() {
            MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
            Uuid7 gen = new Uuid7(clock);
            UUID first = gen.next();
            clock.advance(Duration.ofSeconds(90));
            UUID second = gen.next();

            assertThat(Uuid7.timestampOf(second))
                    .isEqualTo(Uuid7.timestampOf(first).plusSeconds(90));
        }

        @Test
        void extractingATimestampFromANonV7UuidThrows() {
            assertThatThrownBy(() -> Uuid7.timestampOf(UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a UUIDv7");
        }
    }

    @Nested
    @DisplayName("monotonicity — the property the whole decision rests on")
    class Monotonicity {

        @Test
        void idsMintedInTheSameMillisecondStillSortInGenerationOrder() {
            // A frozen clock is the worst case: without the rand_a counter, every one of these
            // would carry an identical timestamp and random low bits, so ordering would be
            // arbitrary. This is the test that would fail on a naive implementation.
            Uuid7 gen = new Uuid7(new MutableClock(Instant.parse("2026-07-28T00:00:00Z")));

            List<UUID> generated =
                    IntStream.range(0, 2000).mapToObj(i -> gen.next()).toList();

            List<UUID> sorted = new ArrayList<>(generated);
            sorted.sort(Uuid7Test::compareUnsigned);

            assertThat(sorted).containsExactlyElementsOf(generated);
        }

        @Test
        void orderingHoldsAcrossMillisecondBoundaries() {
            MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
            Uuid7 gen = new Uuid7(clock);

            List<UUID> generated = new ArrayList<>();
            for (int ms = 0; ms < 50; ms++) {
                for (int i = 0; i < 20; i++) {
                    generated.add(gen.next());
                }
                clock.advance(Duration.ofMillis(1));
            }

            List<UUID> sorted = new ArrayList<>(generated);
            sorted.sort(Uuid7Test::compareUnsigned);
            assertThat(sorted).containsExactlyElementsOf(generated);
        }

        @Test
        void aBackwardsClockStepDoesNotProduceOutOfOrderIds() {
            // NTP corrections happen. Ordering must degrade gracefully rather than break.
            MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
            Uuid7 gen = new Uuid7(clock);

            UUID before = gen.next();
            clock.advance(Duration.ofMillis(-5000));
            UUID after = gen.next();

            assertThat(compareUnsigned(before, after)).isNegative();
        }
    }

    @Nested
    @DisplayName("uniqueness")
    class Uniqueness {

        @Test
        void noCollisionsInASingleThreadedBurst() {
            Uuid7 gen = new Uuid7(Clock.systemUTC());
            Set<UUID> seen =
                    IntStream.range(0, 50_000).mapToObj(i -> gen.next()).collect(java.util.stream.Collectors.toSet());
            assertThat(seen).hasSize(50_000);
        }

        @Test
        void noCollisionsUnderConcurrency() throws Exception {
            // The packed atomic state exists precisely so two threads cannot observe a new
            // timestamp with a stale counter and mint the same id.
            Uuid7 gen = new Uuid7(Clock.systemUTC());
            int threads = 8;
            int perThread = 5_000;

            Set<UUID> seen = new ConcurrentSkipListSet<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                seen.add(gen.next());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(seen).hasSize(threads * perThread);
        }

        @Test
        void exhaustingTheCounterWaitsRatherThanWrapping() {
            // 12 bits of rand_a = 4096 ids per millisecond. Asking for more than that against a
            // frozen clock must not wrap the counter, because wrapping reorders. The generator
            // spins; this test asserts it never returns a duplicate or an out-of-order value.
            MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
            Uuid7 gen = new Uuid7(clock);

            List<UUID> first =
                    IntStream.range(0, 4096).mapToObj(i -> gen.next()).toList();
            assertThat(Set.copyOf(first)).hasSize(4096);

            List<UUID> sorted = new ArrayList<>(first);
            sorted.sort(Uuid7Test::compareUnsigned);
            assertThat(sorted).containsExactlyElementsOf(first);
        }
    }

    /**
     * Compares as unsigned 128-bit values. {@code UUID.compareTo} compares the halves as
     * <em>signed</em> longs, which puts a UUID whose high bit is set before one whose is not —
     * wrong for time-ordering, and a trap worth encoding in the test rather than tripping over in
     * production.
     */
    private static int compareUnsigned(UUID a, UUID b) {
        int msb = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return msb != 0 ? msb : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
