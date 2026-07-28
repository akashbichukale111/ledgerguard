package dev.ledgerguard.transaction.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    /**
     * Claims an idempotency key, atomically.
     *
     * <p>This exists because {@code save()} could not do it. {@link IdempotencyRecordEntity} has an
     * assigned {@code String} id and no {@code @Version}, so Spring Data considers it not-new and
     * calls {@code merge()} rather than {@code persist()}. Merge issues a SELECT first: if the
     * winning request has already committed its row by that point, merge turns the claim into an
     * <b>UPDATE</b> — it silently overwrites the winner's record back to {@code IN_FLIGHT}, raises
     * no constraint violation, and lets the loser go on to write a second aggregate and a second
     * outbox row. That is a duplicated financial instruction from a single client intent, and it is
     * what {@code WritePathIT.concurrentDuplicatesCreateOneAggregate} caught on CI.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the claim one atomic statement. A caller that loses
     * the race gets {@code 0} rather than an exception, which also matters: a constraint violation
     * would mark the transaction rollback-only, so the read that follows — the read that produces
     * the correct replay response — could not be done in it.
     *
     * <p>When a conflicting row exists but is uncommitted, PostgreSQL blocks here until the winner
     * commits and then reports 0 affected rows. That is the desired behaviour: by the time this
     * returns, the winner's record is visible to the follow-up read.
     *
     * @return 1 if this caller claimed the key, 0 if it was already held
     */
    @Modifying
    @Query(
            value =
                    """
            INSERT INTO idempotency_record (idempotency_key, endpoint, request_body_hash, state, created_at)
            VALUES (:key, :endpoint, :bodyHash, 'IN_FLIGHT', :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            nativeQuery = true)
    int tryClaim(
            @Param("key") String idempotencyKey,
            @Param("endpoint") String endpoint,
            @Param("bodyHash") String requestBodyHash,
            @Param("now") Instant now);
}
