package dev.ledgerguard.transaction.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import dev.ledgerguard.transaction.adapter.out.messaging.OutboxPoller;

/**
 * Drives the outbox poller on a fixed interval.
 *
 * <p>The interval is the publish-latency floor: an event is published somewhere between 0 ms and
 * one interval after commit. That cost is the price of polling over CDC, and it is measured rather
 * than estimated (ADR-0005, {@code docs/performance.md}).
 *
 * <p>Separated from the poller itself so integration tests can drive {@code publishBatch()}
 * deterministically instead of waiting for a timer to fire.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ledgerguard.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedule {

    private final OutboxPoller poller;

    public OutboxSchedule(OutboxPoller poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.outbox.poll-interval-ms:500}")
    public void poll() {
        poller.publishBatch();
    }
}
