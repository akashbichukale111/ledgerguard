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

    /**
     * Counts by terminal status, for the dashboard's match and error rates.
     *
     * <p>A count query rather than a scan: these run on every dashboard poll, and the figures must
     * describe the whole collection rather than a sample — a match rate computed off the most recent
     * 200 documents would swing wildly and mean nothing.
     */
    long countByStatus(String status);

    /** How many transactions have reached any terminal outcome. The denominator of every rate. */
    long countByReconciledAtIsNotNull();
}
