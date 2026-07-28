package dev.ledgerguard.common.observability;

import java.util.Optional;

/**
 * Tracing context holder for trace/span IDs.
 *
 * <p>Extracts W3C `traceparent` header format to support OpenTelemetry trace context propagation
 * across HTTP and Kafka boundaries. Format: `00-traceId-spanId-sampled`.
 */
public final class TracingContext {

    private static final String TRACEPARENT_HEADER = "traceparent";

    private final String traceId;
    private final String spanId;
    private final boolean sampled;

    public TracingContext(String traceId, String spanId, boolean sampled) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.sampled = sampled;
    }

    /**
     * Parse W3C traceparent header.
     *
     * <p>Format: 00-traceId-spanId-sampled Example: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
     */
    public static Optional<TracingContext> fromTraceparent(String traceparentHeader) {
        if (traceparentHeader == null || traceparentHeader.trim().isEmpty()) {
            return Optional.empty();
        }

        String[] parts = traceparentHeader.split("-");
        if (parts.length != 4) {
            return Optional.empty();
        }

        try {
            String traceId = parts[1];
            String spanId = parts[2];
            boolean sampled = parts[3].equals("01");
            return Optional.of(new TracingContext(traceId, spanId, sampled));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Format as W3C traceparent header.
     *
     * @return traceparent string (00-traceId-spanId-sampled)
     */
    public String toTraceparent() {
        return String.format("00-%s-%s-%s", traceId, spanId, sampled ? "01" : "00");
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public boolean isSampled() {
        return sampled;
    }
}
