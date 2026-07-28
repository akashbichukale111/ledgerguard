package dev.ledgerguard.query.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DltMessageRepository extends JpaRepository<DltMessageEntity, UUID> {

    /** Newest first, which is the order the console lists them in. */
    List<DltMessageEntity> findAllByOrderByRecordedAtDescMessageIdDesc(Pageable pageable);

    /** Outstanding depth: what an operator still has to deal with. */
    long countByReplayedAtIsNull();

    /**
     * Looks up by origin coordinate so a redelivered dead letter is recognised as the one already
     * stored rather than inserted twice.
     */
    Optional<DltMessageEntity> findBySourceTopicAndPartitionNumberAndRecordOffset(
            String sourceTopic, int partitionNumber, long recordOffset);
}
