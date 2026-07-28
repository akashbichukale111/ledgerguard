package dev.ledgerguard.query.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import dev.ledgerguard.query.adapter.out.persistence.AuditEventEntity;
import dev.ledgerguard.query.adapter.out.persistence.AuditEventRepository;
import dev.ledgerguard.query.domain.audit.AuditHasher;
import dev.ledgerguard.query.domain.audit.ChainVerificationResult;

/**
 * Appends to and verifies the hash-chained audit log.
 *
 * <h2>Why appends are serialised</h2>
 *
 * Each record must observe the hash of the record immediately before it. Two concurrent appends that
 * both read the same head would fork the chain, producing two individually-valid-looking histories
 * with verification silently following one of them.
 *
 * <p>{@code SERIALIZABLE} isolation plus the {@code ux_audit_previous_hash} unique index make that
 * fork impossible rather than unlikely: even if two transactions interleave, the second insert
 * violates the constraint and fails loudly.
 *
 * <p>This is a throughput ceiling on the audit path, and it is the direct reason the chain lives in
 * PostgreSQL rather than MongoDB (ADR-0014).
 */
@Service
public class AuditChainService {

    private static final Logger log = LoggerFactory.getLogger(AuditChainService.class);
    private static final int VERIFY_PAGE_SIZE = 500;

    private final AuditEventRepository repository;
    private final Clock clock;
    private final EntityManager entityManager;

    public AuditChainService(AuditEventRepository repository, Clock clock, EntityManager entityManager) {
        this.repository = repository;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    /**
     * Appends one entry, linking it to the current chain head.
     *
     * <p>The hash cannot be computed before the insert flushes, because {@code chainIndex} is
     * assigned by the database sequence and is part of the canonical form. So: insert, flush to
     * obtain the index, then seal.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AuditEventEntity append(
            UUID eventId,
            Instant occurredAt,
            UUID correlationId,
            UUID causationId,
            String actorSubject,
            String actorRole,
            String actorSource,
            String service,
            String action,
            String aggregateType,
            UUID aggregateId,
            String outcome,
            String beforeState,
            String afterState) {

        String previousHash = repository
                .findFirstByOrderByChainIndexDesc()
                .map(AuditEventEntity::recordHash)
                .orElse(null); // genesis

        AuditEventEntity entity = new AuditEventEntity(
                eventId,
                occurredAt,
                clock.instant(),
                correlationId,
                causationId,
                actorSubject,
                actorRole,
                actorSource,
                service,
                action,
                aggregateType,
                aggregateId,
                outcome,
                beforeState,
                afterState,
                previousHash,
                AuditHasher.FORMAT_VERSION);

        entityManager.persist(entity);
        entityManager.flush();
        entity.seal(AuditHasher.hash(entity.toRecord()));
        return repository.saveAndFlush(entity);
    }

    /**
     * Walks the whole chain and reports the first index at which it breaks.
     *
     * <p>Two independent checks per record, because they catch different attacks:
     *
     * <ul>
     *   <li><b>Content check</b> — recompute the record's hash from its own fields. Catches a row
     *       whose data was edited in place.
     *   <li><b>Link check</b> — the record's {@code previousHash} must equal the preceding record's
     *       {@code recordHash}. Catches a record deleted, inserted, or reordered.
     * </ul>
     *
     * <p>A content-only check would miss a deletion; a link-only check would miss an edit that was
     * re-hashed but not re-linked. Both are needed.
     */
    @Transactional(readOnly = true)
    public ChainVerificationResult verify() {
        long verified = 0;
        long cursor = 0;
        String expectedPrevious = null;
        boolean first = true;

        while (true) {
            List<AuditEventEntity> page = repository.findChainAfter(cursor, PageRequest.of(0, VERIFY_PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }

            for (AuditEventEntity entity : page) {
                long index = entity.chainIndex();

                if (first) {
                    if (entity.previousHash() != null) {
                        return ChainVerificationResult.broken(
                                verified, index, "the first record must have no previous hash");
                    }
                    first = false;
                } else if (!java.util.Objects.equals(entity.previousHash(), expectedPrevious)) {
                    // A record was deleted, inserted, or reordered: the links no longer join up.
                    return ChainVerificationResult.broken(
                            verified,
                            index,
                            "previousHash does not match the preceding record's hash "
                                    + "(record deleted, inserted, or reordered)");
                }

                String recomputed = AuditHasher.hash(entity.toRecord());
                if (!recomputed.equals(entity.recordHash())) {
                    // The row's content changed after it was written.
                    return ChainVerificationResult.broken(
                            verified, index, "record content does not match its stored hash (record was modified)");
                }

                expectedPrevious = entity.recordHash();
                cursor = index;
                verified++;
            }
        }

        log.debug("audit chain verified across {} records", verified);
        return ChainVerificationResult.intact(verified);
    }
}
