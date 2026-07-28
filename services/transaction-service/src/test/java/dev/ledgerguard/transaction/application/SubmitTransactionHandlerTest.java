package dev.ledgerguard.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.ledgerguard.common.core.error.ErrorCode;
import dev.ledgerguard.common.core.id.Uuid7;
import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.transaction.adapter.out.persistence.IdempotencyRecordEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.IdempotencyRepository;
import dev.ledgerguard.transaction.adapter.out.persistence.LedgerEntryEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.LedgerEntryRepository;
import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRecordEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRepository;
import dev.ledgerguard.transaction.adapter.out.persistence.TransactionEntity;
import dev.ledgerguard.transaction.adapter.out.persistence.TransactionRepository;
import dev.ledgerguard.transaction.domain.Direction;

/**
 * The write path's behaviour, held in place without a database.
 *
 * <p>transaction-service had no unit tests at all: its only coverage was {@code WritePathIT}, which
 * needs Docker and has never been executed in this environment. Everything asserted here — the
 * double-entry pair, the outbox write landing in the same call, the idempotent replay, the
 * different-body conflict — was previously unverified by anything that runs.
 */
@DisplayName("SubmitTransactionHandler")
class SubmitTransactionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");
    private static final String KEY = "idem-key-1";

    private TransactionRepository transactions;
    private LedgerEntryRepository ledgerEntries;
    private IdempotencyRepository idempotency;
    private OutboxRepository outbox;
    private SubmitTransactionHandler handler;

    @BeforeEach
    void setUp() {
        transactions = org.mockito.Mockito.mock(TransactionRepository.class);
        ledgerEntries = org.mockito.Mockito.mock(LedgerEntryRepository.class);
        idempotency = org.mockito.Mockito.mock(IdempotencyRepository.class);
        outbox = org.mockito.Mockito.mock(OutboxRepository.class);

        handler = new SubmitTransactionHandler(
                transactions,
                ledgerEntries,
                idempotency,
                outbox,
                new ObjectMapper(),
                new Uuid7(Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SubmitTransactionCommand command() {
        return command("REF-1", new BigDecimal("100.00"));
    }

    private static SubmitTransactionCommand command(String reference, BigDecimal amount) {
        return new SubmitTransactionCommand(
                KEY,
                reference,
                Money.of(amount, CurrencyCode.of("USD")),
                Direction.DEBIT,
                "cp-1",
                "acct-debit",
                "acct-credit",
                LocalDate.of(2026, 1, 15),
                null,
                "SEPA",
                NOW,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                null,
                "operator@example.com",
                null,
                "{\"reference\":\"" + reference + "\",\"amount\":\"" + amount + "\"}");
    }

    /**
     * The claim wins: the pre-check sees nothing, tryClaim inserts a row, and the read-back returns
     * the managed instance the handler completes by dirty checking.
     */
    private IdempotencyRecordEntity claimSucceeds() {
        var claimed = new IdempotencyRecordEntity(KEY, "POST /api/v1/transactions", bodyHashOf(command()), NOW);
        when(idempotency.findById(KEY)).thenReturn(Optional.empty()).thenReturn(Optional.of(claimed));
        when(idempotency.tryClaim(eq(KEY), anyString(), anyString(), any())).thenReturn(1);
        return claimed;
    }

    @Nested
    @DisplayName("first submission")
    class FirstSubmission {

        @Test
        void acceptsAndReturnsTheNewTransactionId() {
            claimSucceeds();

            SubmitTransactionResult result = handler.handle(command());

            assertThat(result.body()).contains("\"status\":\"RECEIVED\"");
            verify(transactions).save(any(TransactionEntity.class));
        }

        @Test
        void writesABalancedDebitAndCreditPair() {
            claimSucceeds();

            handler.handle(command());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerEntryEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(ledgerEntries).saveAll(captor.capture());

            List<LedgerEntryEntity> entries = captor.getValue();
            assertThat(entries).hasSize(2);
            assertThat(entries)
                    .extracting(LedgerEntryEntity::entryType)
                    .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT);
            // Double entry only holds if both legs carry the same amount.
            assertThat(entries.get(0).money()).isEqualTo(entries.get(1).money());
        }

        @Test
        void writesTheOutboxRowInTheSameCall() {
            // The outbox row is what makes the publish survive a crash. If it were written by a
            // separate call after commit, the dual-write problem this design removes comes back.
            claimSucceeds();

            handler.handle(command());

            verify(outbox).save(any(OutboxRecordEntity.class));
        }

        @Test
        void theOutboxPayloadCarriesTheAmountAsAStringNotANumber() {
            // A JSON number would become an IEEE-754 double at the browser boundary and lose
            // exactness. ADR-0009 requires the string form.
            claimSucceeds();

            handler.handle(command("REF-2", new BigDecimal("1234.56")));

            ArgumentCaptor<OutboxRecordEntity> captor = ArgumentCaptor.forClass(OutboxRecordEntity.class);
            verify(outbox).save(captor.capture());
            assertThat(captor.getValue().payload()).contains("\"amount\":\"1234.56\"");
        }

        @Test
        void completesTheIdempotencyRecordSoAReplayCanSucceed() {
            IdempotencyRecordEntity claimed = claimSucceeds();

            handler.handle(command());

            // Completed inside the same transaction: a replay must never see COMPLETED for an
            // aggregate that rolled back. The row is managed, so dirty checking persists this.
            assertThat(claimed.state()).isEqualTo(IdempotencyRecordEntity.State.COMPLETED);
        }

        @Test
        void theClaimIsAnAtomicInsertNotAMergingSave() {
            // Regression guard for the bug WritePathIT.concurrentDuplicatesCreateOneAggregate caught
            // on CI. save()/saveAndFlush() on this entity resolves to merge(), which turns a claim
            // into an UPDATE once the winner has committed — no violation, and the loser goes on to
            // write a second aggregate and a second outbox row.
            claimSucceeds();

            handler.handle(command());

            verify(idempotency).tryClaim(eq(KEY), anyString(), anyString(), any());
            verify(idempotency, never()).saveAndFlush(any(IdempotencyRecordEntity.class));
            verify(idempotency, never()).save(any(IdempotencyRecordEntity.class));
        }
    }

    @Nested
    @DisplayName("replay of the same key")
    class Replay {

        @Test
        void returnsTheStoredResponseWithoutWritingAgain() {
            var completed = new IdempotencyRecordEntity(KEY, "POST /api/v1/transactions", bodyHashOf(command()), NOW);
            completed.complete(202, "{\"transactionId\":\"original\"}", NOW);
            when(idempotency.findById(KEY)).thenReturn(Optional.of(completed));

            SubmitTransactionResult result = handler.handle(command());

            assertThat(result.body()).contains("original");
            // The point of idempotency: the second call must not produce a second transaction.
            verify(transactions, never()).save(any());
            verify(outbox, never()).save(any());
        }

        @Test
        void rejectsTheSameKeyWithADifferentBody() {
            var completed = new IdempotencyRecordEntity(KEY, "POST /api/v1/transactions", "a-different-hash", NOW);
            completed.complete(202, "{}", NOW);
            when(idempotency.findById(KEY)).thenReturn(Optional.of(completed));

            // Returning the stored response here would tell the caller a different request
            // succeeded — the one failure mode idempotency must never have.
            assertThatThrownBy(() -> handler.handle(command()))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }

        @Test
        void rejectsAKeyStillInFlight() {
            var inFlight = new IdempotencyRecordEntity(KEY, "POST /api/v1/transactions", bodyHashOf(command()), NOW);
            when(idempotency.findById(KEY)).thenReturn(Optional.of(inFlight));

            assertThatThrownBy(() -> handler.handle(command()))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT);
        }
    }

    @Nested
    @DisplayName("concurrent claim")
    class ConcurrentClaim {

        /** A winner that has already committed a completed record for the same key. */
        private IdempotencyRecordEntity committedWinner() {
            var winner = new IdempotencyRecordEntity(KEY, "POST /api/v1/transactions", bodyHashOf(command()), NOW);
            winner.complete(202, "{\"transactionId\":\"winner\"}", NOW);
            return winner;
        }

        private void loseTheRace(IdempotencyRecordEntity winner) {
            when(idempotency.findById(KEY)).thenReturn(Optional.empty()).thenReturn(Optional.ofNullable(winner));
            when(idempotency.tryClaim(eq(KEY), anyString(), anyString(), any())).thenReturn(0);
        }

        @Test
        void losingTheRaceFallsBackToTheWinnersStoredResponse() {
            // Two requests with the same key arrive together. The constraint — not a prior read —
            // is what makes this safe, so the loser must read the winner's record and replay it.
            loseTheRace(committedWinner());

            SubmitTransactionResult result = handler.handle(command());

            assertThat(result.body()).contains("winner");
            assertThat(result.replay()).isTrue();
        }

        @Test
        void theLoserWritesNoAggregateAndNoOutboxRow() {
            // The heart of it: one client intent must produce one financial instruction. This is
            // the invariant WritePathIT.concurrentDuplicatesCreateOneAggregate found violated on CI,
            // because save() resolved to merge() and quietly overwrote the winner's claim.
            loseTheRace(committedWinner());

            handler.handle(command());

            verify(transactions, never()).save(any());
            verify(ledgerEntries, never()).saveAll(any());
            verify(outbox, never()).save(any());
        }

        @Test
        void aConflictWithNoReadableRecordFailsLoudly() {
            // tryClaim reported a conflict, so a row must exist. If it cannot be read, something
            // other than a key collision went wrong and guessing would hide it.
            loseTheRace(null);

            assertThatThrownBy(() -> handler.handle(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conflicted");
        }
    }

    @Nested
    @DisplayName("command invariants")
    class CommandInvariants {

        @Test
        void aNegativeAmountIsRejected() {
            // Direction carries the sign; encoding it in the amount makes every aggregation
            // ambiguous.
            assertThatThrownBy(() -> command("REF", new BigDecimal("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        void aZeroAmountIsRejected() {
            assertThatThrownBy(() -> command("REF", BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero");
        }
    }

    /** Mirrors the handler's body hash so replay fixtures line up with what it computes. */
    private static String bodyHashOf(SubmitTransactionCommand command) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(
                            digest.digest(command.canonicalBody().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
