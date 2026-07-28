package dev.ledgerguard.common.observability;

import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;

/**
 * Kafka consumer utilities for extracting trace context from message headers.
 *
 * <p>Reads W3C `traceparent` header from Kafka message and populates MDC so downstream
 * processing inherits the trace context.
 */
public final class KafkaTracingConsumer {

    /**
     * Extract tracing context from consumer record and populate MDC.
     *
     * <p>Reads `traceparent` and `correlationId` headers and sets MDC fields for the
     * current thread.
     *
     * @param record the ConsumerRecord to extract headers from
     * @param <K> key type
     * @param <V> value type
     */
    public static <K, V> void populateMdcFromHeaders(ConsumerRecord<K, V> record) {
        Headers headers = record.headers();

        Optional<TracingContext> tracingCtx = KafkaTracingProducer.extractTracingContext(headers);
        if (tracingCtx.isPresent()) {
            TracingContext ctx = tracingCtx.get();
            MdcContext.put(MdcContext.TRACE_ID, ctx.getTraceId());
            MdcContext.put(MdcContext.SPAN_ID, ctx.getSpanId());
        }

        Optional<String> correlationId = KafkaTracingProducer.extractCorrelationId(headers);
        if (correlationId.isPresent()) {
            MdcContext.put(MdcContext.CORRELATION_ID, correlationId.get());
        }
    }

    /**
     * Clear MDC after message processing.
     *
     * <p>Call this after successfully processing a Kafka message to prevent MDC leakage
     * between messages in thread pool scenarios.
     */
    public static void clearMdc() {
        MdcContext.clear();
    }

    private KafkaTracingConsumer() {}
}
