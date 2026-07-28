package dev.ledgerguard.query.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    /** The current chain head, which the next append links to. */
    Optional<AuditEventEntity> findFirstByOrderByChainIndexDesc();

    /**
     * Walks the chain in order.
     *
     * <p>Paged so verification of a large log does not load the whole table into memory. Verification
     * is O(n) by nature — the ADR states that as a known cost.
     */
    @Query("select a from AuditEventEntity a where a.chainIndex > :after order by a.chainIndex")
    List<AuditEventEntity> findChainAfter(@Param("after") long after, Pageable pageable);

    List<AuditEventEntity> findByCorrelationIdOrderByChainIndexAsc(UUID correlationId);
}
