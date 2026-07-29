package dev.ledgerguard.query.application;

import java.util.function.Supplier;

import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Lets the read side answer a query when MongoDB is absent or unreachable.
 *
 * <p><b>Why this exists.</b> The projections live in MongoDB, and the hosted demo has no free
 * managed Kafka, Postgres and Mongo from one vendor. Rather than let the console show a wall of
 * 500s when the read model is not configured, every projection-backed read routes through here and
 * degrades to an empty answer that says so.
 *
 * <p><b>What this deliberately does not do.</b> It does not fabricate data. A degraded read returns
 * empty or null and the response carries {@code readModelAvailable=false}, so the console can
 * render "the read model is not configured" rather than an empty list that looks like "no
 * transactions exist". Those are different facts and an operator has to be able to tell them
 * apart — the same reason the dashboard reports a null match rate rather than 0%.
 *
 * <p>The write path is unaffected. Transactions are still accepted, still written to Postgres and
 * still published to Kafka; only the projected view of them is missing.
 */
@Component
public class ReadModelAvailability {
    private static final Logger log = LoggerFactory.getLogger(ReadModelAvailability.class);

    /** Placeholder host from application-cloud.yml, meaning "no URI was supplied". */
    private static final String UNCONFIGURED_HOST = "read-model-not-configured";

    private final boolean configured;

    public ReadModelAvailability(
            @Value("${ledgerguard.read-model.enabled:true}") boolean enabled,
            @Value("${spring.data.mongodb.uri:}") String mongoUri) {
        // Two independent ways to be off: an explicit switch, or a URI that is absent or still the
        // placeholder. Checking the URI too means forgetting the switch cannot produce a service
        // that spends every request timing out against a host that does not exist.
        this.configured = enabled && !mongoUri.isBlank() && !mongoUri.contains(UNCONFIGURED_HOST);

        if (!configured) {
            log.warn("read model is NOT configured — projection-backed endpoints will report themselves "
                    + "unavailable. The write path is unaffected. Set MONGO_URI to enable it.");
        }
    }

    /** Whether projection-backed answers reflect real data. */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Runs a projection query, falling back rather than propagating.
     *
     * @param query the read to attempt
     * @param degraded what to answer with when the read model is off or unreachable
     */
    public <T> T query(Supplier<T> query, T degraded) {
        if (!configured) {
            return degraded;
        }
        try {
            return query.get();
        } catch (DataAccessException | MongoException e) {
            // Warn, not error: on a free tier an unreachable read model is an expected operating
            // condition, and logging it at ERROR would train an operator to ignore ERROR.
            log.warn("read model unreachable, degrading this response: {}", e.getMessage());
            return degraded;
        }
    }
}
