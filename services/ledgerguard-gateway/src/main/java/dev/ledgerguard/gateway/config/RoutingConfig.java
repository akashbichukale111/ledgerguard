package dev.ledgerguard.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * The edge routing table.
 *
 * <p>Declared in Java rather than YAML so the ordering constraint below is enforced by the compiler
 * reading top to bottom, and so it can be asserted by a test.
 *
 * <p><b>Order is load-bearing.</b> Gateway evaluates routes in declaration order and takes the first
 * match. {@code /api/v1/auth/**} and the transaction write path are both subsets of
 * {@code /api/v1/**}; declaring the catch-all first would silently send every login to the query
 * service, which does not implement it.
 *
 * <p>The write/read split matters too: a {@code POST /api/v1/transactions} is an instruction and
 * belongs to transaction-service, while {@code GET /api/v1/transactions/...} is a projection read
 * and belongs to query-service. This is the CQRS boundary made visible at the edge — the same path,
 * routed by method.
 */
@Configuration
public class RoutingConfig {

    private final String authServerUri;
    private final String transactionServiceUri;
    private final String queryServiceUri;

    public RoutingConfig(
            @Value("${ledgerguard.gateway.auth-server:http://localhost:8084}") String authServerUri,
            @Value("${ledgerguard.gateway.transaction-service:http://localhost:8081}") String transactionServiceUri,
            @Value("${ledgerguard.gateway.query-service:http://localhost:8083}") String queryServiceUri) {
        this.authServerUri = authServerUri;
        this.transactionServiceUri = transactionServiceUri;
        this.queryServiceUri = queryServiceUri;
    }

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                // 1. Login. Must precede the catch-all.
                .route("auth", r -> r.path("/api/v1/auth/**").uri(authServerUri))

                // 2. The write side. Method-scoped: only a POST creates an instruction.
                .route("transaction-write", r -> r.method(HttpMethod.POST)
                        .and()
                        .path("/api/v1/transactions")
                        .uri(transactionServiceUri))

                // 3. Everything else on the API is a read, served by the projections.
                .route("query", r -> r.path("/api/v1/**").uri(queryServiceUri))
                .build();
    }
}
