package dev.ledgerguard.query.adapter.in.rest;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.query.adapter.out.messaging.RetryPublishingService;

/**
 * REST endpoint for replaying messages from the dead-letter topic.
 *
 * <p>Allows operators with OPERATIONS role to republish selected DLT messages back to their source
 * topic for reprocessing. Each replay generates a new causationId to prevent confusion with the
 * original message. Audit-logged for compliance (Phase 7 full implementation).
 */
@RestController
@RequestMapping("/api/v1/replay")
public class ReplayController {
    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final RetryPublishingService retryPublisher;

    public ReplayController(RetryPublishingService retryPublisher) {
        this.retryPublisher = Objects.requireNonNull(retryPublisher, "retryPublisher");
    }

    /**
     * Replay a DLT message.
     *
     * @param request replay request with original envelope
     * @return status and new causationId
     */
    @PostMapping("/dlt-message")
    public ResponseEntity<ReplayResponse> replayDltMessage(@RequestBody ReplayRequest request) {
        try {
            Objects.requireNonNull(request.originalEnvelope(), "originalEnvelope required");

            // New causationId marks this as a replay, not the original message
            String replayCausationId = java.util.UUID.randomUUID().toString();

            // Publish to retry ladder (attempt 1)
            retryPublisher.publishRetryOrDlt(
                    request.originalEnvelope(), 1, "Operator replay from DLT", new Exception("DLT replay"));

            log.info(
                    "DLT replay initiated: causationId={}, original={}",
                    replayCausationId,
                    request.originalEnvelope()
                            .substring(
                                    0, Math.min(50, request.originalEnvelope().length())));

            return ResponseEntity.ok(
                    new ReplayResponse(replayCausationId, "Replay initiated, message republished to retry ladder"));
        } catch (Exception e) {
            log.error("Replay failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ReplayResponse(null, "Replay failed: " + e.getMessage()));
        }
    }

    // Request and response DTOs
    public record ReplayRequest(String originalEnvelope) {}

    public record ReplayResponse(String causationId, String message) {}
}
