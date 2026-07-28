package dev.ledgerguard.query.adapter.in.rest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.query.adapter.in.rest.dto.TransactionSummary;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Document;
import dev.ledgerguard.query.adapter.out.mongo.Transaction360Repository;

/**
 * Read side of the transaction projection — the endpoints the console's search and detail views
 * call. Until now they had no controller behind them and every request 404'd.
 *
 * <p>Everything here reads the Transaction 360 document and never the write model, which is the
 * point of the split.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionQueryController {

    /** Bounds an unbounded client. Without a ceiling a single request can ask for the collection. */
    private static final int MAX_LIMIT = 200;

    private final Transaction360Repository transactions;

    public TransactionQueryController(Transaction360Repository transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * Search by transaction id, reference, counterparty or correlation id.
     *
     * <p>A blank query returns the most recent transactions, which is what the console shows when
     * the search box is empty.
     *
     * <p>The filtering is done in the service rather than the database. That is a deliberate
     * shortcut: it is correct but it reads a page and narrows it, so it does not scale to a large
     * collection. A text index on the projection is the real fix — see the phase-17 report.
     */
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/search")
    public List<TransactionSummary> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        List<Transaction360Document> page =
                transactions.findAllByOrderByOccurredAtDescTransactionIdDesc(PageRequest.of(0, capped));

        if (query == null || query.isBlank()) {
            return page.stream().map(TransactionSummary::withoutTimeline).toList();
        }

        String needle = query.trim().toLowerCase();
        return page.stream()
                .filter(d -> matches(d, needle))
                .map(TransactionSummary::withoutTimeline)
                .toList();
    }

    private static boolean matches(Transaction360Document d, String needle) {
        return contains(d.getTransactionId(), needle)
                || contains(d.getReference(), needle)
                || contains(d.getCounterpartyId(), needle)
                || (d.getCorrelationId() != null
                        && d.getCorrelationId().toString().toLowerCase().contains(needle));
    }

    private static boolean contains(String field, String needle) {
        return field != null && field.toLowerCase().contains(needle);
    }

    /** Full projection for one transaction, timeline included. */
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionSummary> byId(@PathVariable String transactionId) {
        return transactions
                .findById(transactionId)
                .map(TransactionSummary::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Just the lifecycle timeline.
     *
     * <p>Separate from the detail call because the console's trace view polls it while a
     * transaction is still moving, and it does not need the rest of the document each time.
     */
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/{transactionId}/lifecycle")
    public ResponseEntity<List<TransactionSummary.LifecycleStep>> lifecycle(@PathVariable String transactionId) {
        return transactions
                .findById(transactionId)
                .map(d -> TransactionSummary.from(d).timeline())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Everything sharing a correlation id — one business action across services. */
    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/by-correlation/{correlationId}")
    public ResponseEntity<List<TransactionSummary>> byCorrelation(@PathVariable String correlationId) {
        UUID parsed;
        try {
            parsed = UUID.fromString(correlationId);
        } catch (IllegalArgumentException notAUuid) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(transactions.findByCorrelationId(parsed).stream()
                .map(TransactionSummary::withoutTimeline)
                .toList());
    }
}
