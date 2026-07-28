package dev.ledgerguard.common.observability;

import java.util.Optional;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * Kafka producer utilities for propagating trace context through message headers.
 *
 * <p>W3C `traceparent` header (00-traceId-spanId-sampled) is written to Kafka message headers
 * so the message can be traced across broker hops.
 */
public final class KafkaTracingProducer {

    private static final String TRACEPARENT = "traceparent";
    private static final String CORRELATION_ID = "correlationId";

    /**
     * Add tracing headers to a Kafka ProducerRecord.
     *
     * <p>Extracts current traceId/spanId from MDC and writes to `traceparent` header.
     *
     * @param record the ProducerRecord to enrich
     * @param <K> key type
     * @param <V> value type
     * @return the same record with headers updated
     */
    public static <K, V> ProducerRecord<K, V> enrichWithTracingHeaders(ProducerRecord<K, V> record) {
        String traceId = MdcContext.get(MdcContext.TRACE_ID);
        String spanId = MdcContext.get(MdcContext.SPAN_ID);
        String correlationId = MdcContext.get(MdcContext.CORRELATION_ID);

        if (traceId != null && spanId != null) {
            TracingContext ctx = new TracingContext(traceId, spanId, true);
            record.headers()
                    .add(new RecordHeader(TRACEPARENT, ctx.toTraceparent().getBytes()));
        }

        if (correlationId != null) {
            record.headers().add(new RecordHeader(CORRELATION_ID, correlationId.getBytes()));
        }

        return record;
    }

    /**
     * Extract tracing context from Kafka message headers.
     *
     * <p>Reads `traceparent` header and returns TracingContext if present.
     *
     * @param headers Kafka message headers
     * @return TracingContext if traceparent header found, empty otherwise
     */
    public static Optional<TracingContext> extractTracingContext(Headers headers) {
        Header traceparentHeader = headers.lastHeader(TRACEPARENT);
        if (traceparentHeader == null) {
            return Optional.empty();
        }

        String traceparentValue = new String(traceparentHeader.value());
        return TracingContext.fromTraceparent(traceparentValue);
    }

    /**
     * Extract correlationId from Kafka message headers.
     *
     * @param headers Kafka message headers
     * @return correlationId if present, empty otherwise
     */
    public static Optional<String> extractCorrelationId(Headers headers) {
        Header correlationHeader = headers.lastHeader(CORRELATION_ID);
        if (correlationHeader == null) {
            return Optional.empty();
        }

        return Optional.of(new String(correlationHeader.value()));
    }

    private KafkaTracingProducer() {}
}
