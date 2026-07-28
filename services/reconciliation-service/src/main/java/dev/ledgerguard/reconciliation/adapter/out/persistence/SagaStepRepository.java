package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepRepository extends JpaRepository<SagaStepEntity, Long> {
    List<SagaStepEntity> findBySagaIdOrderByStepNumberAscAttemptAsc(UUID sagaId);
}
