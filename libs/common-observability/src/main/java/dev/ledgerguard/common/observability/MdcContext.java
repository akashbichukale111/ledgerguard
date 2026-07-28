package dev.ledgerguard.common.observability;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.MDC;

/**
 * Managed Diagnostic Context (MDC) holder for structured logging.
 *
 * <p>Stable field schema for all logs: timestamp, level, service, traceId, spanId,
 * correlationId, actor, eventType, aggregateId, message, errorCode.
 *
 * <p>MDC fields are thread-local in Logback; use put()/clear() to manage context for the
 * current thread.
 */
public final class MdcContext {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String ACTOR = "actor";
    public static final String EVENT_TYPE = "eventType";
    public static final String AGGREGATE_ID = "aggregateId";
    public static final String ERROR_CODE = "errorCode";
    public static final String SERVICE = "service";

    private static final ThreadLocal<Map<String, String>> context = ThreadLocal.withInitial(HashMap::new);

    /**
     * Put a value into MDC.
     *
     * @param key MDC key (use public constants)
     * @param value MDC value (null clears the key)
     */
    public static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
            context.get().remove(key);
        } else {
            MDC.put(key, value);
            context.get().put(key, value);
        }
    }

    /**
     * Get a value from MDC.
     *
     * @param key MDC key
     * @return value or null if not set
     */
    public static String get(String key) {
        return MDC.get(key);
    }

    /**
     * Clear all MDC fields.
     */
    public static void clear() {
        MDC.clear();
        context.get().clear();
    }

    /**
     * Put multiple MDC fields at once.
     *
     * @param fields map of key-value pairs
     */
    public static void putAll(Map<String, String> fields) {
        fields.forEach(MdcContext::put);
    }

    private MdcContext() {}
}
