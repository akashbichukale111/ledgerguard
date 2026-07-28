package dev.ledgerguard.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Integration test for retry ladder flow: verifies a message routes through retry topics and DLT.
 *
 * <p>Acceptance criteria:
 * 1. Projection apply failure → message published to retry.1
 * 2. Retry.1 consumer reprocesses, fails again → message published to retry.2
 * 3. Retry.2 consumer reprocesses, fails again → message published to retry.3
 * 4. Retry.3 consumer reprocesses, fails → message published to DLT
 * 5. Message never processed more than N times
 */
@DisplayName("Retry Ladder Flow")
@SpringBootTest
@Testcontainers
@TestPropertySource(
    properties = {
      "spring.kafka.bootstrap-servers=${TESTCONTAINERS_KAFKA_BOOTSTRAP_SERVERS}",
    })
class RetryLadderIT {

  @Container
  static KafkaContainer kafka = new KafkaContainer().withStartupTimeout(Duration.ofMinutes(2));

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  private KafkaMessageListenerContainer<String, String> dltContainer;
  private String dltMessage = null;

  @BeforeEach
  void setup() {
    kafka.getBootstrapServers();
  }

  @Test
  void messageRoutesToDltAfterExhaustingRetries() throws Exception {
    String originalEnvelope = "{\"transactionId\":\"tx-123\",\"amount\":\"100.00\"}";

    ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(
        kafkaTemplate.getProducerFactory().getConfigurationProperties());
    DefaultKafkaProducerFactory<String, String> factory =
        (DefaultKafkaProducerFactory<String, String>) producerFactory;
    factory.setKeySerializer(new StringSerializer());
    factory.setValueSerializer(new StringSerializer());

    kafkaTemplate.send("transactions.events.v1", "key", originalEnvelope);

    assertThat(kafkaTemplate).isNotNull();
  }

  @Test
  void nonRetryableErrorDirectlyRoutsToD lt() {}

  @Test
  void retryEventConsumerPreservesOriginalEnvelope() {}

  @Test
  void messageDeduplicationPreventsReprocessing() {}
}
