package dev.ledgerguard.reconciliation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import dev.ledgerguard.reconciliation.adapter.out.messaging.ReconciliationEventPublisher;
import dev.ledgerguard.reconciliation.adapter.out.persistence.CompensationRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaInstanceRepository;
import dev.ledgerguard.reconciliation.adapter.out.persistence.SagaStepRepository;
import dev.ledgerguard.reconciliation.application.SagaOrchestrator;

/**
 * Guards the wiring of {@link ReconciliationConfig} without a database or a broker.
 *
 * <p><b>Why this exists.</b> Phase 19 shipped a publisher that injects an {@code ObjectMapper} into a
 * service whose classpath had no {@code spring-web}. Boot builds its auto-configured
 * {@code ObjectMapper} through {@code Jackson2ObjectMapperBuilder}, which lives in that jar — so
 * jackson-databind arriving transitively was enough to <em>compile</em> and not enough to produce a
 * <em>bean</em>. Nothing caught it: unit tests construct the publisher directly, and the only test
 * that refreshed the real context needed Docker, so it ran solely on CI.
 *
 * <p>The lesson was not "read CI sooner". It was that a context this service depends on was never
 * asserted anywhere a developer could run it. {@link ApplicationContextRunner} refreshes the same
 * bean definitions in milliseconds against mocked infrastructure, which is where a missing bean
 * belongs — surefire, not a container.
 */
class ReconciliationConfigContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // A bare runner has no conversion service, so the `PT5M` saga timeout would never become
            // a Duration. Boot installs this in a real application; installing it here keeps the
            // test honest about what it is proving — that the wiring works, not that a stripped-down
            // context happens to tolerate it.
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ReconciliationConfig.class)
            // Persistence and the broker are the parts this test deliberately does not exercise;
            // stubbing them leaves the wiring itself as the only thing under assertion.
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(SagaInstanceRepository.class, () -> mock(SagaInstanceRepository.class))
            .withBean(SagaStepRepository.class, () -> mock(SagaStepRepository.class))
            .withBean(CompensationRepository.class, () -> mock(CompensationRepository.class));

    @Test
    void everyBeanTheServiceDeclaresCanBeConstructed() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(ReconciliationEventPublisher.class)
                .hasSingleBean(SagaOrchestrator.class));
    }

    @Test
    void anObjectMapperBeanIsAvailableOnThisModulesClasspath() {
        // The exact failure from CI: without spring-web this assertion is what goes red, here,
        // in a second, instead of thirteen integration tests failing to load a context.
        runner.run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
    }

    @Test
    void theAutoConfiguredMapperWritesInstantsAsIso8601NotEpochDecimals() {
        // The envelope carries occurredAt and reconciledAt. Jackson's raw default renders an Instant
        // as "1.768473E9", which no consumer parses back and no schema accepts. Boot's
        // auto-configuration turns that off — this pins that the publisher gets Boot's mapper and
        // not a bare `new ObjectMapper()` someone substitutes later.
        runner.run(context -> {
            String json = context.getBean(ObjectMapper.class).writeValueAsString(Instant.parse("2026-01-15T10:30:00Z"));
            assertThat(json).isEqualTo("\"2026-01-15T10:30:00Z\"");
        });
    }
}
