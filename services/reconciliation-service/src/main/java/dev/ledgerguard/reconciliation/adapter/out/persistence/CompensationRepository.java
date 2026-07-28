package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompensationRepository extends JpaRepository<CompensationRecordEntity, Long> {
    /** Ordered by execution so a test can assert compensation ran in reverse step order. */
    List<CompensationRecordEntity> findBySagaIdOrderByIdAsc(UUID sagaId);
}
