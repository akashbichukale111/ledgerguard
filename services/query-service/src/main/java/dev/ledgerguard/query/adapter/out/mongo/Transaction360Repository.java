package dev.ledgerguard.query.adapter.out.mongo;

import java.util.List;
import java.util.UUID;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface Transaction360Repository extends MongoRepository<Transaction360Document, String> {

    List<Transaction360Document> findByCorrelationId(UUID correlationId);

    /**
     * Keyset page: everything strictly older than the cursor.
     *
     * <p>The {@code occurredAt} + {@code transactionId} pair is the cursor. The tiebreaker is
     * mandatory — many transactions share a timestamp, and without it rows at a page boundary are
     * skipped or repeated (ADR-0008).
     */
    List<Transaction360Document> findByOccurredAtLessThanOrderByOccurredAtDescTransactionIdDesc(
            java.time.Instant before, org.springframework.data.domain.Pageable pageable);

    List<Transaction360Document> findAllByOrderByOccurredAtDescTransactionIdDesc(
            org.springframework.data.domain.Pageable pageable);
}
