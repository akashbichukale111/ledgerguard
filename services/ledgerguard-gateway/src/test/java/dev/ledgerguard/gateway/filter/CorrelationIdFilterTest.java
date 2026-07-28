package dev.ledgerguard.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /** Runs the filter and returns the request as it would be forwarded downstream. */
    private ServerHttpRequest forward(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        var captured = new ServerWebExchange[1];

        filter.filter(exchange, downstream -> {
                    captured[0] = downstream;
                    return Mono.empty();
                })
                .block();

        return captured[0].getRequest();
    }

    private String headerOn(MockServerHttpRequest request) {
        return forward(request).getHeaders().getFirst(CorrelationIdFilter.HEADER);
    }

    @Test
    void mintsAnIdWhenTheCallerSuppliesNone() {
        String minted =
                headerOn(MockServerHttpRequest.get("/api/v1/metrics/dashboard").build());

        assertThat(minted).isNotNull();
        assertThat(UUID.fromString(minted)).isNotNull();
    }

    @Test
    void honoursAValidCallerSuppliedId() {
        // A client that already correlates its own work must keep its identifier across the
        // boundary, or its logs and ours cannot be joined.
        String supplied = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(headerOn(MockServerHttpRequest.get("/api/v1/metrics/dashboard")
                        .header(CorrelationIdFilter.HEADER, supplied)
                        .build()))
                .isEqualTo(supplied);
    }

    @Test
    void normalisesCasingSoOneFlowDoesNotAppearTwiceInASearch() {
        String supplied = "550E8400-E29B-41D4-A716-446655440000";

        assertThat(headerOn(MockServerHttpRequest.get("/x")
                        .header(CorrelationIdFilter.HEADER, supplied)
                        .build()))
                .isEqualTo(supplied.toLowerCase());
    }

    @Test
    void replacesAMalformedIdRatherThanForwardingIt() {
        // An unvalidated header lets a caller inject arbitrary text into every downstream log line.
        String result = headerOn(MockServerHttpRequest.get("/x")
                .header(CorrelationIdFilter.HEADER, "not-a-uuid'; DROP TABLE audit_event;--")
                .build());

        assertThat(result).isNotEqualTo("not-a-uuid'; DROP TABLE audit_event;--");
        assertThat(UUID.fromString(result)).isNotNull();
    }

    @Test
    void replacesABlankId() {
        String result = headerOn(MockServerHttpRequest.get("/x")
                .header(CorrelationIdFilter.HEADER, "   ")
                .build());

        assertThat(UUID.fromString(result)).isNotNull();
    }

    @Test
    void echoesTheIdOnTheResponseSoACallerCanQuoteIt() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        filter.filter(exchange, downstream -> Mono.empty()).block();

        String onRequest = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        String onResponse = exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER);

        // The original exchange's request is unmutated; what matters is that the response carries
        // the same value the downstream request was given.
        assertThat(onResponse).isNotNull();
        assertThat(UUID.fromString(onResponse)).isNotNull();
        assertThat(onRequest).isNull();
    }

    @Test
    void runsBeforeRoutingSoTheHeaderIsOnTheForwardedRequest() {
        assertThat(filter.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }
}
