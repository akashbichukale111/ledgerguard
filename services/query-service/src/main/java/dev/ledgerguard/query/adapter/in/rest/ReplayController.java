package dev.ledgerguard.query.adapter.in.rest;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.common.security.RbacMatrix;
import dev.ledgerguard.common.security.Role;
import dev.ledgerguard.query.adapter.out.messaging.RetryPublishingService;
import dev.ledgerguard.query.config.SecurityConfig;

/**
 * REST endpoint for replaying messages from the dead-letter topic.
 *
 * <p>Allows operators to republish selected DLT messages back to the retry ladder for reprocessing.
 * Each replay generates a new causationId so a replayed message is distinguishable from the
 * original.
 *
 * <p>Authorization runs twice, against the same authenticated principal: {@code @PreAuthorize}
 * rejects the request before the method body runs, and {@link RbacMatrix} is consulted inside for
 * the operation-level decision. Both read the caller's granted authorities — an earlier version
 * passed a hardcoded {@code Role.OPERATIONS} to the matrix, which made the second check a constant
 * that could never fail.
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
     * Replay a DLT message back onto the retry ladder.
     *
     * @param request replay request carrying the original envelope
     * @param authentication the caller, supplied by Spring Security
     * @return the new causationId, or an error status
     */
    @PreAuthorize("hasRole('OPERATIONS')")
    @PostMapping("/dlt-message")
    public ResponseEntity<ReplayResponse> replayDltMessage(
            @RequestBody ReplayRequest request, Authentication authentication) {

        if (request == null
                || request.originalEnvelope() == null
                || request.originalEnvelope().isBlank()) {
            return ResponseEntity.badRequest().body(new ReplayResponse(null, "originalEnvelope is required"));
        }

        Optional<Role> callerRole = highestRoleOf(authentication);
        if (callerRole.isEmpty() || !RbacMatrix.canPerform(callerRole.get(), RbacMatrix.Operation.DLT_REPLAY)) {
            log.warn(
                    "Denied DLT replay for principal={} role={}",
                    authentication == null ? "anonymous" : authentication.getName(),
                    callerRole.map(Enum::name).orElse("none"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ReplayResponse(null, "Operation not permitted for your role"));
        }

        // A fresh causationId marks this as a replay rather than the original delivery.
        String replayCausationId = UUID.randomUUID().toString();

        try {
            retryPublisher.publishRetryOrDlt(
                    request.originalEnvelope(), 1, "Operator replay from DLT", new ReplayRequested(replayCausationId));
        } catch (RuntimeException e) {
            log.error("Replay publish failed for causationId={}", replayCausationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ReplayResponse(replayCausationId, "Replay failed: " + e.getMessage()));
        }

        log.info("DLT replay initiated by {}: causationId={}", authentication.getName(), replayCausationId);

        return ResponseEntity.ok(
                new ReplayResponse(replayCausationId, "Replay initiated, message republished to retry ladder"));
    }

    /**
     * Maps the caller's granted authorities onto the role hierarchy, returning the most privileged
     * role they hold. Authorities that do not correspond to a {@link Role} are ignored.
     */
    private static Optional<Role> highestRoleOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(SecurityConfig.ROLE_PREFIX))
                .map(a -> a.substring(SecurityConfig.ROLE_PREFIX.length()))
                .flatMap(name -> {
                    try {
                        return java.util.stream.Stream.of(Role.valueOf(name));
                    } catch (IllegalArgumentException notARole) {
                        return java.util.stream.Stream.empty();
                    }
                })
                // Role is declared most-privileged-first, so the lowest ordinal wins.
                .min(java.util.Comparator.comparingInt(Enum::ordinal));
    }

    /** Marker cause recorded on the retry envelope so replays are identifiable downstream. */
    static final class ReplayRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReplayRequested(String causationId) {
            super("Operator replay, causationId=" + causationId);
        }
    }

    public record ReplayRequest(String originalEnvelope) {}

    public record ReplayResponse(String causationId, String message) {}
}
