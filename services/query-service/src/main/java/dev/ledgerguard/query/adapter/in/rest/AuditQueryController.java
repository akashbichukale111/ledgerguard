package dev.ledgerguard.query.adapter.in.rest;

import java.time.Instant;
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

import dev.ledgerguard.query.adapter.out.persistence.AuditEventEntity;
import dev.ledgerguard.query.adapter.out.persistence.AuditEventRepository;
import dev.ledgerguard.query.application.AuditChainService;
import dev.ledgerguard.query.domain.audit.AuditRecord;

/**
 * Read side of the audit chain — the console's audit trail view.
 *
 * <p>Reading the audit log is itself a privileged act: entries name who did what to which
 * aggregate, so {@code AUDIT_VIEW} is gated at ANALYST and chain verification at OPERATIONS.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditQueryController {

    private static final int MAX_LIMIT = 500;

    private final AuditEventRepository auditEvents;
    private final AuditChainService auditChain;

    public AuditQueryController(AuditEventRepository auditEvents, AuditChainService auditChain) {
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents");
        this.auditChain = Objects.requireNonNull(auditChain, "auditChain");
    }

    /** One audit entry as the console renders it. */
    public record AuditEntryView(
            long chainIndex,
            String eventId,
            Instant occurredAt,
            String actor,
            String actorRole,
            String action,
            String service,
            String aggregateType,
            String aggregateId,
            String outcome,
            String correlationId,
            String recordHash) {

        static AuditEntryView from(AuditEventEntity entity) {
            AuditRecord r = entity.toRecord();
            return new AuditEntryView(
                    r.chainIndex(),
                    r.eventId().toString(),
                    r.occurredAt(),
                    r.actorSubject(),
                    r.actorRole(),
                    r.action(),
                    r.service(),
                    r.aggregateType(),
                    r.aggregateId() == null ? null : r.aggregateId().toString(),
                    r.outcome(),
                    r.correlationId().toString(),
                    entity.recordHash());
        }
    }

    /**
     * Recent audit entries, optionally narrowed by actor.
     *
     * <p>The actor filter is applied after the page is read rather than in the query. Same tradeoff
     * as transaction search, and the same fix — an index-backed query — is deferred; see phase-17.
     */
    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/entries")
    public List<AuditEntryView> entries(
            @RequestParam(name = "correlationId", required = false) String correlationId,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        if (correlationId != null && !correlationId.isBlank()) {
            return byCorrelation(correlationId).getBody();
        }

        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        // chainIndex is the total order, so "after 0" walked in index order and capped is the
        // oldest-first page; the console reverses for display.
        List<AuditEventEntity> page = auditEvents.findChainAfter(0L, PageRequest.of(0, capped));

        return page.stream()
                .filter(e -> actor == null
                        || actor.isBlank()
                        || actor.equalsIgnoreCase(e.toRecord().actorSubject()))
                .map(AuditEntryView::from)
                .toList();
    }

    /** Every entry sharing a correlation id — the full story of one business action. */
    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/entries/{correlationId}")
    public ResponseEntity<List<AuditEntryView>> byCorrelation(@PathVariable String correlationId) {
        UUID parsed;
        try {
            parsed = UUID.fromString(correlationId);
        } catch (IllegalArgumentException notAUuid) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(auditEvents.findByCorrelationIdOrderByChainIndexAsc(parsed).stream()
                .map(AuditEntryView::from)
                .toList());
    }

    /**
     * Verifies the hash chain end to end.
     *
     * <p>O(n) over the log by design — the guarantee is that tampering is detectable, and detecting
     * it means recomputing every link.
     */
    @PreAuthorize("hasRole('OPERATIONS')")
    @GetMapping("/verify")
    public ResponseEntity<?> verify() {
        return ResponseEntity.ok(auditChain.verify());
    }
}
