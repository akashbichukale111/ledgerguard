package dev.ledgerguard.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TracingContext")
class TracingContextTest {

    @Nested
    @DisplayName("W3C traceparent parsing")
    class W3cTraceparentParsing {

        @Test
        void parsesValidTraceparentHeader() {
            String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

            Optional<TracingContext> result = TracingContext.fromTraceparent(traceparent);

            assertThat(result).isPresent().hasValueSatisfying(ctx -> {
                assertThat(ctx.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
                assertThat(ctx.getSpanId()).isEqualTo("b7ad6b7169203331");
                assertThat(ctx.isSampled()).isTrue();
            });
        }

        @Test
        void parsesUnsampledTraceparent() {
            String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00";

            Optional<TracingContext> result = TracingContext.fromTraceparent(traceparent);

            assertThat(result).isPresent().hasValueSatisfying(ctx -> assertThat(ctx.isSampled())
                    .isFalse());
        }

        @Test
        void returnsEmptyForInvalidFormat() {
            assertThat(TracingContext.fromTraceparent("invalid")).isEmpty();
            assertThat(TracingContext.fromTraceparent("00-id-span")).isEmpty();
            assertThat(TracingContext.fromTraceparent(null)).isEmpty();
            assertThat(TracingContext.fromTraceparent("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Traceparent formatting")
    class TraceparentFormatting {

        @Test
        void formatsTraceparentHeaderCorrectly() {
            TracingContext ctx = new TracingContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", true);

            String result = ctx.toTraceparent();

            assertThat(result).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        }

        @Test
        void formatsUnsampledTraceparent() {
            TracingContext ctx = new TracingContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", false);

            String result = ctx.toTraceparent();

            assertThat(result).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00");
        }
    }

    @Nested
    @DisplayName("Round-trip conversion")
    class RoundTripConversion {

        @Test
        void roundTripPreservesContext() {
            String original = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

            TracingContext parsed = TracingContext.fromTraceparent(original).orElseThrow();
            String reformatted = parsed.toTraceparent();

            assertThat(reformatted).isEqualTo(original);
        }
    }
}
