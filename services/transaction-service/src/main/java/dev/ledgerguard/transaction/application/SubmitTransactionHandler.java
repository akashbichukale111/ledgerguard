package dev.ledgerguard.transaction.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.ledgerguard.common.core.error.ErrorCode;
import dev.ledgerguard.common.core.id.Uuid7;
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
 * Accepts a transaction instruction.
 *
 * <p>This is the write path's entry point and the place the reliability story is either true or
 * not. The whole method body runs in <b>one local ACID transaction</b> that spans:
 *
 * <ol>
 *   <li>the idempotency record,
 *   <li>the transaction aggregate,
 *   <li>its ledger entries,
 *   <li>the outbox row.
 * </ol>
 *
 * <p>Either all four are durable or none are. That is what makes the outbox pattern work and what
 * removes the dual-write problem (ADR-0005). Splitting any of them into a second transaction —
 * particularly "publish then save" — reintroduces exactly the bug this design exists to prevent.
 */
@Service
public class SubmitTransactionHandler {

    private static final Logger log = LoggerFactory.getLogger(SubmitTransactionHandler.class);

    private static final String ENDPOINT = "POST /api/v1/transactions";
    private static final String AGGREGATE_TYPE = "Transaction";
    private static final String EVENT_TYPE = "TransactionReceived";
    private static final int EVENT_VERSION = 2;
    private static final String SCHEMA_REF = "schemas/TransactionReceived/v2.json";

    private final TransactionRepository transactions;
    private final LedgerEntryRepository ledgerEntries;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Uuid7 ids;
    private final Clock clock;

    public SubmitTransactionHandler(
            TransactionRepository transactions,
            LedgerEntryRepository ledgerEntries,
            IdempotencyRepository idempotency,
            OutboxRepository outbox,
            ObjectMapper objectMapper,
            Uuid7 ids,
            Clock clock) {
        this.transactions = transactions;
        this.ledgerEntries = ledgerEntries;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public SubmitTransactionResult handle(SubmitTransactionCommand command) {
        Instant now = clock.instant();
        String bodyHash = sha256(command.canonicalBody());

        Optional<IdempotencyRecordEntity> existing = idempotency.findById(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), bodyHash, command);
        }

        // Claim the key first. If a concurrent request beat us to it, the primary key rejects this
        // insert and we fall into the replay path — the constraint, not a prior read, is what makes
        // this safe.
        IdempotencyRecordEntity claim;
        try {
            // The RETURNED instance must be used, not the one passed in. This entity has an
            // assigned (non-generated) String id and no @Version, so Spring Data treats it as
            // not-new and calls merge() rather than persist(). merge() returns a DIFFERENT managed
            // instance and leaves the argument detached — mutating the argument later would be
            // silently discarded at commit, and the idempotency record would stay IN_FLIGHT
            // forever, turning every legitimate replay into a 409.
            claim = idempotency.saveAndFlush(
                    new IdempotencyRecordEntity(command.idempotencyKey(), ENDPOINT, bodyHash, now));
        } catch (DataIntegrityViolationException e) {
            log.debug("idempotency key {} claimed concurrently", command.idempotencyKey());
            IdempotencyRecordEntity winner = idempotency
                    .findById(command.idempotencyKey())
                    .orElseThrow(() -> e); // genuinely unexpected: rethrow rather than guess
            return replayOrConflict(winner, bodyHash, command);
        }

        UUID transactionId = ids.next();
        Money amount = command.amount();

        TransactionEntity transaction = new TransactionEntity(
                transactionId,
                command.reference(),
                command.counterpartyId(),
                amount,
                command.direction(),
                command.valueDate(),
                command.postingDate(),
                command.settlementSystem(),
                command.occurredAt(),
                now,
                command.correlationId());
        transactions.save(transaction);

        // Double-entry: every instruction produces a balanced pair. The invariant that debits equal
        // credits per currency is enforced here, in the domain, rather than by a database
        // constraint, because it spans rows.
        List<LedgerEntryEntity> entries = List.of(
                new LedgerEntryEntity(
                        ids.next(),
                        transactionId,
                        command.debitAccount(),
                        Direction.DEBIT,
                        amount,
                        command.valueDate(),
                        command.effectivePostingDate(),
                        now),
                new LedgerEntryEntity(
                        ids.next(),
                        transactionId,
                        command.creditAccount(),
                        Direction.CREDIT,
                        amount,
                        command.valueDate(),
                        command.effectivePostingDate(),
                        now));
        ledgerEntries.saveAll(entries);

        UUID eventId = ids.next();
        outbox.save(new OutboxRecordEntity(
                eventId,
                AGGREGATE_TYPE,
                transactionId,
                EVENT_TYPE,
                EVENT_VERSION,
                serialisePayload(transactionId, command),
                serialiseHeaders(command, eventId),
                command.occurredAt(),
                now));

        // Complete the idempotency record inside the same transaction, so a replay can never
        // observe a COMPLETED record whose aggregate was rolled back.
        String responseBody = serialiseResponse(transactionId);
        claim.complete(202, responseBody, now);

        log.info(
                "transaction accepted transactionId={} correlationId={} eventId={}",
                transactionId,
                command.correlationId(),
                eventId);

        return SubmitTransactionResult.accepted(transactionId, responseBody);
    }

    private SubmitTransactionResult replayOrConflict(
            IdempotencyRecordEntity record, String bodyHash, SubmitTransactionCommand command) {

        if (!record.requestBodyHash().equals(bodyHash)) {
            // Returning the stored response here would tell the caller that a DIFFERENT request
            // succeeded. Failing loudly is the only safe answer.
            throw new IdempotencyConflictException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key '%s' was already used with a different request body"
                            .formatted(command.idempotencyKey()));
        }

        if (record.state() == IdempotencyRecordEntity.State.IN_FLIGHT) {
            throw new IdempotencyConflictException(
                    ErrorCode.IDEMPOTENT_REQUEST_IN_FLIGHT,
                    "A request with idempotency key '%s' is still in flight".formatted(command.idempotencyKey()));
        }

        log.debug("idempotent replay for key {}", command.idempotencyKey());
        return SubmitTransactionResult.replay(record.responseStatus(), record.responseBody());
    }

    private String serialisePayload(UUID transactionId, SubmitTransactionCommand c) {
        try {
            // amount is a STRING, never a JSON number: JSON.parse would turn a number into an
            // IEEE-754 double and destroy it at the browser boundary (ADR-0009).
            return objectMapper.writeValueAsString(Map.of(
                    "transactionId", transactionId.toString(),
                    "reference", c.reference(),
                    "amount", c.amount().amount().toPlainString(),
                    "currency", c.amount().currency().code(),
                    "valueDate", c.valueDate().toString(),
                    "postingDate", c.effectivePostingDate().toString(),
                    "counterpartyId", c.counterpartyId(),
                    "direction", c.direction().name(),
                    "settlementSystem",
                            Optional.ofNullable(c.settlementSystem()).orElse("")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise event payload", e);
        }
    }

    private String serialiseHeaders(SubmitTransactionCommand c, UUID eventId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "correlationId", c.correlationId().toString(),
                    "causationId",
                            Optional.ofNullable(c.causationId())
                                    .map(UUID::toString)
                                    .orElse(""),
                    "schemaRef", SCHEMA_REF,
                    "actorSubject", c.actorSubject(),
                    "traceparent", Optional.ofNullable(c.traceparent()).orElse("")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise event headers", e);
        }
    }

    private String serialiseResponse(UUID transactionId) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("transactionId", transactionId.toString(), "status", "RECEIVED"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise response", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
