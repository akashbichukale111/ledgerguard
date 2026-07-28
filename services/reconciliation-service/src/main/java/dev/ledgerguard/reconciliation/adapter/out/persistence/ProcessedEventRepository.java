package dev.ledgerguard.reconciliation.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, ProcessedEventEntity.Key> {}
