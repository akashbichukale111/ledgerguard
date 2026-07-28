package dev.ledgerguard.reconciliation.config;

import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import dev.ledgerguard.common.core.id.Uuid7;
import dev.ledgerguard.common.core.money.CurrencyCode;
import dev.ledgerguard.reconciliation.adapter.out.messaging.ReconciliationEventPublisher;
import dev.ledgerguard.reconciliation.adapter.out.persistence.CompensationRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaInstanceRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaStepRepository;
import dev.ledgerguard.reconciliation.application.SagaOrchestrator;
import dev.ledgerguard.reconciliation.domain.matching.BusinessCalendar;
import dev.ledgerguard.reconciliation.domain.matching.MatchingConfig;
import dev.ledgerguard.reconciliation.domain.matching.ReconciliationEngine;

@Configuration
public class ReconciliationConfig {

    /** All time comes from here so tests can advance a fixed clock instead of sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Uuid7 uuid7(Clock clock) {
        return new Uuid7(clock);
    }

    @Bean
    public BusinessCalendar businessCalendar() {
        // Weekends only. Public holidays are not modelled; see BusinessCalendar's javadoc for the
        // consequence, which is stated rather than hidden.
        return BusinessCalendar.weekendsOnly();
    }

    @Bean
    public ReconciliationEngine reconciliationEngine(BusinessCalendar calendar) {
        return new ReconciliationEngine(MatchingConfig.defaultConfig(CurrencyCode.of("USD")), calendar);
    }

    /**
     * Built here rather than component-scanned because the topic name is configuration and cannot be
     * autowired as a bare String.
     */
    @Bean
    public ReconciliationEventPublisher reconciliationEventPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${ledgerguard.topics.reconciliation:reconciliation.events.v1}") String topic) {
        return new ReconciliationEventPublisher(kafka, objectMapper, clock, topic);
    }

    @Bean
    public SagaOrchestrator sagaOrchestrator(
            SagaInstanceRepository instances,
            SagaStepRepository steps,
            CompensationRepository compensations,
            Clock clock,
            @Value("${ledgerguard.saga.timeout:PT5M}") Duration timeout) {
        return new SagaOrchestrator(instances, steps, compensations, clock, timeout);
    }
}
