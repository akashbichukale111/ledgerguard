package dev.ledgerguard.gateway.filter;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Mints a correlation ID at the edge when the caller did not supply one.
 *
 * <p>This is the only place in the system that can guarantee every request has one. A service that
 * mints its own would produce a different ID per hop, which is precisely the failure a correlation
 * ID exists to prevent — one business flow would appear as four unrelated ones in the audit trail.
 *
 * <p>A caller-supplied ID is honoured rather than overwritten, so a client that already correlates
 * its own work keeps its identifier across the boundary. It is validated as a UUID first: an
 * unvalidated header would let a caller inject arbitrary text into every downstream log line.
 *
 * <p>The ID is echoed on the response so a caller can quote it in a support request without having
 * generated it themselves.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));

        ServerHttpRequest mutated =
                exchange.getRequest().mutate().header(HEADER, correlationId).build();

        // Echoed before the chain runs: setting it afterwards is too late once the response has
        // begun to commit.
        exchange.getResponse().getHeaders().set(HEADER, correlationId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** Keeps a valid caller-supplied ID; mints one otherwise. */
    private static String resolve(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            // Parse and re-render rather than pass through: this both validates the shape and
            // normalises the casing, so the same flow does not appear twice in a log search.
            return UUID.fromString(supplied.trim()).toString();
        } catch (IllegalArgumentException notAUuid) {
            log.debug("discarding malformed {} header", HEADER);
            return UUID.randomUUID().toString();
        }
    }

    @Override
    public int getOrder() {
        // Before routing, so the header is present on the request that is actually forwarded.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
