package dev.ledgerguard.query.adapter.in.rest;

import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a read-model outage into an answer the console can render.
 *
 * <p>Without this, an unreachable MongoDB surfaces as a bare 500 with a stack trace, and the
 * console shows an unexplained error on every projection-backed page. The write path is still
 * working in that state — transactions are accepted, persisted and published — so presenting it as
 * a total failure misrepresents what is actually wrong.
 *
 * <p><b>503, not 200-with-empty-list.</b> An empty list would be a lie: it says "there are no
 * transactions" when the truth is "this service cannot see them". 503 with
 * {@code readModelAvailable: false} lets the console draw an explicit empty state, and tells any
 * other caller that retrying later is reasonable — which a 500 does not.
 *
 * <p>The dashboard endpoint does not reach here; {@code DashboardMetricsService} degrades in place
 * so the console's landing page still loads. See {@code ReadModelAvailability}.
 */
@RestControllerAdvice
public class ReadModelUnavailableAdvice {
    private static final Logger log = LoggerFactory.getLogger(ReadModelUnavailableAdvice.class);

    private static final String DETAIL =
            "Projections are served from MongoDB, which is not configured or not reachable. "
                    + "The write path is unaffected: transactions are still accepted and published.";

    /** @param readModelAvailable always false; present so one client-side check covers every payload */
    public record ReadModelUnavailable(boolean readModelAvailable, String error, String detail) {}

    @ExceptionHandler({DataAccessException.class, MongoException.class})
    public ResponseEntity<ReadModelUnavailable> readModelDown(Exception e) {
        // The message, not the stack: on a free tier this is an expected condition, and a stack
        // trace per request would bury anything genuinely wrong.
        log.warn("read model unavailable for this request: {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                // Tells a caller this is transient and roughly when to come back. A 500 carries no
                // such signal, so clients either hammer or give up.
                .header("Retry-After", "30")
                .body(new ReadModelUnavailable(false, "read model unavailable", DETAIL));
    }
}
