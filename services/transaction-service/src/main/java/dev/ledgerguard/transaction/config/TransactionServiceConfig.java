package dev.ledgerguard.transaction.config;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import dev.ledgerguard.common.core.id.Uuid7;
import dev.ledgerguard.transaction.adapter.out.messaging.EventPublisher;
import dev.ledgerguard.transaction.adapter.out.messaging.KafkaEventPublisher;
import dev.ledgerguard.transaction.adapter.out.messaging.OutboxPoller;
import dev.ledgerguard.transaction.adapter.out.persistence.OutboxRepository;

@Configuration
public class TransactionServiceConfig {

    /**
     * Every timestamp in the service comes from here.
     *
     * <p>Injecting the clock is what lets tests drive saga timeouts and SLA expiry by advancing a
     * fixed clock instead of sleeping. An ArchUnit rule bans {@code Instant.now()} so this cannot be
     * quietly bypassed.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Uuid7 uuid7(Clock clock) {
        return new Uuid7(clock);
    }

    @Bean
    public EventPublisher eventPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${ledgerguard.kafka.topics.transactions:transactions.events.v1}") String topic) {
        return new KafkaEventPublisher(kafka, objectMapper, clock, topic);
    }

    @Bean
    public OutboxPoller outboxPoller(
            OutboxRepository outbox,
            EventPublisher publisher,
            Clock clock,
            MeterRegistry meters,
            @Value("${ledgerguard.outbox.batch-size:100}") int batchSize) {
        return new OutboxPoller(outbox, publisher, clock, meters, batchSize);
    }
}
