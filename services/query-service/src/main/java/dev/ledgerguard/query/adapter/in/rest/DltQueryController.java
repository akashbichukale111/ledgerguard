package dev.ledgerguard.query.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.query.adapter.out.persistence.DltMessageEntity;
import dev.ledgerguard.query.adapter.out.persistence.DltMessageRepository;

/**
 * Lists captured dead letters for the console's DLT explorer.
 *
 * <p>Replay lives on {@link ReplayController} and needs OPERATIONS. Listing is separated and only
 * needs ANALYST: seeing that messages are failing is diagnostic, putting them back on a topic is
 * an action with consequences.
 */
@RestController
@RequestMapping("/api/v1/replay")
public class DltQueryController {

    private static final int MAX_LIMIT = 200;

    private final DltMessageRepository dltMessages;

    public DltQueryController(DltMessageRepository dltMessages) {
        this.dltMessages = Objects.requireNonNull(dltMessages, "dltMessages");
    }

    /** A dead letter as the console renders it. */
    public record DltMessageView(
            String messageId,
            String topic,
            int partition,
            long offset,
            String reason,
            String stackTraceDigest,
            int attemptCount,
            Instant firstFailedAt,
            Instant timestamp,
            Instant replayedAt,
            String originalEnvelope) {

        static DltMessageView from(DltMessageEntity e) {
            return new DltMessageView(
                    e.getMessageId().toString(),
                    e.getSourceTopic(),
                    e.getPartitionNumber(),
                    e.getRecordOffset(),
                    e.getReason(),
                    e.getStackTraceDigest(),
                    e.getAttemptCount(),
                    e.getFirstFailedAt(),
                    e.getOccurredAt(),
                    e.getReplayedAt(),
                    e.getOriginalEnvelope());
        }
    }

    /**
     * Captured dead letters, newest first.
     *
     * <p>Note the offset parameter is accepted for the console's paging control but only the first
     * page is honoured — see the phase-17 report; keyset paging over {@code recorded_at} is the
     * intended fix and the index for it already exists.
     */
    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/dlt-messages")
    public List<DltMessageView> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {

        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        int page = Math.max(0, offset / Math.max(1, capped));

        return dltMessages.findAllByOrderByRecordedAtDescMessageIdDesc(PageRequest.of(page, capped)).stream()
                .map(DltMessageView::from)
                .toList();
    }
}
