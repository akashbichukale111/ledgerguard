package dev.ledgerguard.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("MdcContext")
class MdcContextTest {

    @BeforeEach
    void setup() {
        MDC.clear();
        MdcContext.clear();
    }

    @Nested
    @DisplayName("MDC API contract")
    class MDCApiContract {

        @Test
        void putAcceptsKeyAndValue() {
            MdcContext.put("key", "value");
            MdcContext.clear();
        }

        @Test
        void putWithNullValueClears() {
            MdcContext.put("key", null);
        }

        @Test
        void getReturnsNullForUnsetKey() {
            assertThat(MdcContext.get("nonexistent")).isNull();
        }

        @Test
        void clearDoesNotThrow() {
            MdcContext.put("key", "value");
            MdcContext.clear();
        }

        @Test
        void putAllAcceptsMap() {
            var fields = java.util.Map.of(MdcContext.TRACE_ID, "trace-123", MdcContext.SPAN_ID, "span-456");

            MdcContext.putAll(fields);
            MdcContext.clear();
        }
    }

    @Nested
    @DisplayName("Field schema constants")
    class FieldSchemaConstants {

        @Test
        void allSchemaConstantsAreDefined() {
            assertThat(MdcContext.TRACE_ID).isEqualTo("traceId");
            assertThat(MdcContext.SPAN_ID).isEqualTo("spanId");
            assertThat(MdcContext.CORRELATION_ID).isEqualTo("correlationId");
            assertThat(MdcContext.ACTOR).isEqualTo("actor");
            assertThat(MdcContext.EVENT_TYPE).isEqualTo("eventType");
            assertThat(MdcContext.AGGREGATE_ID).isEqualTo("aggregateId");
            assertThat(MdcContext.ERROR_CODE).isEqualTo("errorCode");
            assertThat(MdcContext.SERVICE).isEqualTo("service");
        }
    }
}
