package dev.ledgerguard.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Applies response security headers at the edge.
 *
 * <p>Set here rather than in each service so a new service cannot ship without them. The nginx
 * config in front of the console sets an overlapping set for the static assets; this covers the API
 * responses, which nginx proxies rather than serves.
 *
 * <p>Headers are set with {@code setIfAbsent} semantics — a downstream service that has a specific
 * reason to differ keeps its own value rather than being silently overridden.
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // beforeCommit, because headers cannot be added once the response has started writing.
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();

            // Stops a browser second-guessing a declared content type, which is how a JSON
            // response gets executed as script.
            setIfAbsent(headers, "X-Content-Type-Options", "nosniff");

            // The API is not a document to be embedded.
            setIfAbsent(headers, "X-Frame-Options", "DENY");

            // Do not leak API paths to third-party sites via the Referer header.
            setIfAbsent(headers, "Referrer-Policy", "no-referrer");

            // An API response should never be the document a browser renders, so nothing may load.
            setIfAbsent(headers, "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");

            // Financial data must not sit in a shared cache.
            setIfAbsent(headers, "Cache-Control", "no-store");

            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    private static void setIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        // After correlation-ID minting; the ordering between these two is not significant beyond
        // both running before the response commits.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
