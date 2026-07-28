package dev.ledgerguard.transaction.adapter.in.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.validation.Valid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.common.core.error.ErrorCode;
import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.common.core.money.Money;
import dev.ledgerguard.transaction.application.SubmitTransactionCommand;
import dev.ledgerguard.transaction.application.SubmitTransactionHandler;
import dev.ledgerguard.transaction.application.SubmitTransactionResult;
import dev.ledgerguard.transaction.domain.Direction;

/**
 * The command API.
 *
 * <p>Controllers depend on the application layer, never on repositories — the classic layer-skip
 * that turns a layered design into a plausible-looking mess. An ArchUnit rule enforces it.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final SubmitTransactionHandler handler;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TransactionController(SubmitTransactionHandler handler, ObjectMapper objectMapper, Clock clock) {
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @Valid @RequestBody SubmitTransactionRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Required, not optional: without it a client retry after a timeout creates a second
            // financial instruction, and the client cannot tell that it did.
            throw new MissingIdempotencyKeyException();
        }

        CurrencyCode currency = CurrencyCode.of(request.currency());
        Money amount = Money.of(new BigDecimal(request.amount()), currency);

        UUID correlationId = correlationHeader != null && !correlationHeader.isBlank()
                ? UUID.fromString(correlationHeader)
                : UUID.randomUUID();

        SubmitTransactionCommand command = new SubmitTransactionCommand(
                idempotencyKey,
                request.reference(),
                amount,
                Direction.valueOf(request.direction()),
                request.counterpartyId(),
                request.debitAccount(),
                request.creditAccount(),
                request.valueDate(),
                request.postingDate(),
                request.settlementSystem(),
                clock.instant(),
                correlationId,
                null,
                "system",
                traceparent,
                canonicalise(request));

        SubmitTransactionResult result = handler.handle(command);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        result.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("X-Correlation-Id", correlationId.toString());
        if (result.replay()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.body());
    }

    /**
     * Produces a field-order-independent rendering of the request for hashing.
     *
     * <p>Hashing the raw bytes would make two semantically identical requests with different JSON
     * key order hash differently, so a legitimate retry from a client that reserialises would be
     * rejected as a conflicting reuse. A {@link TreeMap} fixes the order.
     */
    private String canonicalise(SubmitTransactionRequest r) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("reference", r.reference());
        fields.put("amount", new BigDecimal(r.amount()).stripTrailingZeros().toPlainString());
        fields.put("currency", r.currency());
        fields.put("direction", r.direction());
        fields.put("counterpartyId", r.counterpartyId());
        fields.put("debitAccount", r.debitAccount());
        fields.put("creditAccount", r.creditAccount());
        fields.put("valueDate", r.valueDate().toString());
        fields.put("postingDate", r.postingDate() == null ? "" : r.postingDate().toString());
        fields.put("settlementSystem", r.settlementSystem() == null ? "" : r.settlementSystem());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not canonicalise request", e);
        }
    }

    /** Raised when the required {@code Idempotency-Key} header is absent. */
    public static class MissingIdempotencyKeyException extends RuntimeException {
        public MissingIdempotencyKeyException() {
            super(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.description());
        }
    }
}
