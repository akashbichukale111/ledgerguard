package dev.ledgerguard.transaction.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxRecordEntity, Long> {

    /**
     * Claims a batch of unpublished records for this poller instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what allows several publisher instances to run
     * concurrently: a row already locked by another poller is skipped rather than blocked on, so
     * throughput scales with instances instead of serialising on the oldest row.
     *
     * <p>Ordered by {@code id} so that events for one aggregate keep their relative order — the
     * identity column is monotonic, and the Kafka key preserves ordering from there.
     */
    @Query(
            """
            select o from OutboxRecordEntity o
            where o.publishedAt is null
            order by o.id
            """)
    @QueryHints({@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxRecordEntity> claimUnpublished(org.springframework.data.domain.Pageable pageable);

    long countByPublishedAtIsNull();

    List<OutboxRecordEntity> findByAggregateIdOrderByIdAsc(UUID aggregateId);

    @Query("select count(o) from OutboxRecordEntity o where o.eventId = :eventId")
    long countByEventId(@Param("eventId") UUID eventId);
}
