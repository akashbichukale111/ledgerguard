package dev.ledgerguard.reconciliation.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaInstanceRepository extends JpaRepository<SagaInstanceEntity, UUID> {

    Optional<SagaInstanceEntity> findByTransactionId(UUID transactionId);

    /**
     * Instances past their deadline that have not reached a terminal state.
     *
     * <p>Filtering on {@code completedAt is null} rather than on the state list means a state added
     * later cannot accidentally be swept.
     */
    @Query(
            """
            select s from SagaInstanceEntity s
            where s.completedAt is null and s.deadlineAt < :now
            order by s.deadlineAt
            """)
    List<SagaInstanceEntity> findExpired(@Param("now") Instant now);
}
