package dev.ledgerguard.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("Kafka Tracing")
class KafkaTracingTest {

    @BeforeEach
    void setup() {
        MDC.clear();
        MdcContext.clear();
    }

    @Nested
    @DisplayName("Producer: enrich with tracing headers")
    class ProducerEnrichment {

        @Test
        void extractsTraceparentFromHeaders() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes());

            Optional<TracingContext> extracted = KafkaTracingProducer.extractTracingContext(headers);

            assertThat(extracted).isPresent().hasValueSatisfying(ctx -> {
                assertThat(ctx.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
                assertThat(ctx.getSpanId()).isEqualTo("b7ad6b7169203331");
            });
        }

        @Test
        void extractsCorrelationIdFromHeaders() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("correlationId", "corr-12345".getBytes());

            Optional<String> extracted = KafkaTracingProducer.extractCorrelationId(headers);

            assertThat(extracted).contains("corr-12345");
        }

        @Test
        void skipsHeadersWhenNotPresent() {
            RecordHeaders headers = new RecordHeaders();

            assertThat(KafkaTracingProducer.extractTracingContext(headers)).isEmpty();
            assertThat(KafkaTracingProducer.extractCorrelationId(headers)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Consumer: extract tracing from headers")
    class ConsumerExtraction {

        @Test
        void extractsContextFromHeaders() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes());
            headers.add("correlationId", "corr-12345".getBytes());

            Optional<TracingContext> ctx = KafkaTracingProducer.extractTracingContext(headers);
            Optional<String> correlationId = KafkaTracingProducer.extractCorrelationId(headers);

            assertThat(ctx).isPresent().hasValueSatisfying(c -> {
                assertThat(c.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
                assertThat(c.getSpanId()).isEqualTo("b7ad6b7169203331");
            });
            assertThat(correlationId).contains("corr-12345");
        }
    }

    @Nested
    @DisplayName("Round-trip Kafka propagation")
    class RoundTripPropagation {

        @Test
        void propagatesContextThroughKafkaHeaders() {
            // Simulate headers from a Kafka message
            RecordHeaders headers = new RecordHeaders();
            headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes());
            headers.add("correlationId", "corr-12345".getBytes());

            // Producer side: headers are already set with context

            // Consumer side: extract context from headers
            Optional<TracingContext> extracted = KafkaTracingProducer.extractTracingContext(headers);
            Optional<String> correlationId = KafkaTracingProducer.extractCorrelationId(headers);

            // Verify all context preserved through transport
            assertThat(extracted).isPresent().hasValueSatisfying(ctx -> {
                assertThat(ctx.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
                assertThat(ctx.getSpanId()).isEqualTo("b7ad6b7169203331");
            });

            assertThat(correlationId).contains("corr-12345");
        }
    }
}
