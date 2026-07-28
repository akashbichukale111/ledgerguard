package dev.ledgerguard.query.adapter.in.rest;

import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.ledgerguard.query.application.DashboardMetricsService;

/**
 * Operational figures for the console header and dashboard.
 *
 * <p>Distinct from {@code /actuator/prometheus}, which carries the Micrometer registry for
 * scraping. This endpoint answers "what should an operator see right now", and every value is
 * computed from data the service holds — see {@link DashboardMetricsService} for what is
 * deliberately absent.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final DashboardMetricsService metrics;

    public MetricsController(DashboardMetricsService metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/dashboard")
    public DashboardMetricsService.DashboardMetrics dashboard() {
        return metrics.current();
    }

    /** Worst observed projection lag, in milliseconds. */
    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/projection-lag")
    public long projectionLag() {
        return metrics.current().projectionLagMillis();
    }

    /** Dead letters awaiting operator action. */
    @PreAuthorize("hasRole('ANALYST')")
    @GetMapping("/dlt-depth")
    public long dltDepth() {
        return metrics.current().dltDepth();
    }
}
