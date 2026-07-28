package dev.ledgerguard.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The routing table's ordering contract.
 *
 * <p>Gateway takes the first matching route, and both the auth path and the transaction write path
 * are subsets of the {@code /api/v1/**} catch-all. Declaring them in the wrong order sends every
 * login to a service that does not implement it — a failure that only shows up at runtime, which is
 * why it is pinned here.
 */
@DisplayName("RoutingConfig")
@SpringBootTest
@TestPropertySource(
        properties = {
            "ledgerguard.gateway.auth-server=http://auth-host:8084",
            "ledgerguard.gateway.transaction-service=http://write-host:8081",
            "ledgerguard.gateway.query-service=http://query-host:8083"
        })
class RoutingConfigTest {

    private static final String AUTH = "http://auth-host:8084";
    private static final String WRITE = "http://write-host:8081";
    private static final String QUERY = "http://query-host:8083";

    /**
     * The real locator from the running context. Building one by hand is not equivalent: the
     * predicate factories come from Gateway's auto-configuration, so a hand-built builder produces
     * a table that cannot evaluate anything.
     */
    @Autowired
    private RouteLocator routeLocator;

    private List<Route> routes() {
        return routeLocator.getRoutes().collectList().block();
    }

    /** The first route whose predicate accepts this request — exactly what Gateway does at runtime. */
    private String targetFor(HttpMethod method, String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        return routes().stream()
                .filter(route -> Boolean.TRUE.equals(
                        Mono.from(route.getPredicate().apply(exchange)).block()))
                .findFirst()
                .map(route -> route.getUri().toString())
                .orElse(null);
    }

    @Test
    void loginGoesToTheAuthServerNotTheCatchAll() {
        assertThat(targetFor(HttpMethod.POST, "/api/v1/auth/login")).isEqualTo(AUTH);
    }

    @Test
    void postingATransactionGoesToTheWriteSide() {
        assertThat(targetFor(HttpMethod.POST, "/api/v1/transactions")).isEqualTo(WRITE);
    }

    @Test
    void readingTransactionsGoesToTheQuerySideEvenOnTheSamePath() {
        // The CQRS boundary made visible at the edge: same path, routed by method.
        assertThat(targetFor(HttpMethod.GET, "/api/v1/transactions")).isEqualTo(QUERY);
    }

    @Test
    void aTransactionSubpathReadGoesToTheQuerySide() {
        assertThat(targetFor(HttpMethod.GET, "/api/v1/transactions/tx-1/lifecycle"))
                .isEqualTo(QUERY);
    }

    @Test
    void everythingElseOnTheApiFallsThroughToTheQueryService() {
        assertThat(targetFor(HttpMethod.GET, "/api/v1/metrics/dashboard")).isEqualTo(QUERY);
        assertThat(targetFor(HttpMethod.GET, "/api/v1/audit/entries")).isEqualTo(QUERY);
        assertThat(targetFor(HttpMethod.POST, "/api/v1/replay/dlt-message")).isEqualTo(QUERY);
    }

    @Test
    void anUnknownPathMatchesNothingRatherThanLeakingToAService() {
        assertThat(targetFor(HttpMethod.GET, "/internal/secrets")).isNull();
    }

    @Test
    void theAuthRouteIsDeclaredBeforeTheCatchAll() {
        // The assertion above proves the behaviour; this pins the cause, so a reordering that
        // happens to still pass gets an explicit failure explaining why order matters.
        List<String> ids = routes().stream().map(Route::getId).toList();

        assertThat(ids.indexOf("auth")).isLessThan(ids.indexOf("query"));
        assertThat(ids.indexOf("transaction-write")).isLessThan(ids.indexOf("query"));
    }
}
