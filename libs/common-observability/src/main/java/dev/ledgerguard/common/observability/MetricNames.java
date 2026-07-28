package dev.ledgerguard.common.observability;

/**
 * Standardized metric names across the platform.
 *
 * <p>Naming convention: service.component.operation.metric (e.g., query.projection.apply.duration).
 * Metrics are measured via MeterRegistry and must correspond to real code paths.
 */
public final class MetricNames {

    // ===== Transaction Service Metrics =====

    public static final String TXN_INGESTION_RATE = "txn.ingestion.rate";
    public static final String TXN_INGESTION_ERRORS = "txn.ingestion.errors";
    public static final String TXN_INGESTION_DURATION = "txn.ingestion.duration";

    public static final String TXN_OUTBOX_LAG_ROWS = "txn.outbox.lag.rows";
    public static final String TXN_OUTBOX_LAG_AGE_MS = "txn.outbox.lag.age.ms";

    // ===== Reconciliation Service Metrics =====

    public static final String RECON_SAGA_INITIATED = "recon.saga.initiated";
    public static final String RECON_SAGA_COMPLETED = "recon.saga.completed";
    public static final String RECON_SAGA_COMPENSATED = "recon.saga.compensated";
    public static final String RECON_SAGA_TIMEOUT = "recon.saga.timeout";
    public static final String RECON_SAGA_FAILURES = "recon.saga.failures";

    public static final String RECON_MATCH_RATE = "recon.match.rate";
    public static final String RECON_AUTO_MATCH_PCT = "recon.auto.match.pct";
    public static final String RECON_MANUAL_MATCH_COUNT = "recon.manual.match.count";

    public static final String RECON_COMPENSATION_FAILURES = "recon.compensation.failures";

    // ===== Query Service Metrics =====

    public static final String QUERY_PROJECTION_LAG = "query.projection.lag";
    public static final String QUERY_PROJECTION_APPLY_DURATION = "query.projection.apply.duration";
    public static final String QUERY_PROJECTION_APPLY_ERRORS = "query.projection.apply.errors";

    public static final String QUERY_CONSUMER_LAG = "query.consumer.lag";
    public static final String QUERY_CONSUMER_LAG_AGE_MS = "query.consumer.lag.age.ms";

    public static final String QUERY_IDEMPOTENT_REPLAY_COUNT = "query.idempotent.replay.count";

    // ===== Kafka/DLT Metrics =====

    public static final String KAFKA_DLT_DEPTH = "kafka.dlt.depth";
    public static final String KAFKA_RETRY_TOPIC_DEPTH = "kafka.retry.topic.depth";

    public static final String KAFKA_CONSUMER_LAG = "kafka.consumer.lag";
    public static final String KAFKA_PRODUCER_ERRORS = "kafka.producer.errors";

    // ===== Error Classification Metrics =====

    public static final String ERRORS_RETRYABLE = "errors.retryable.count";
    public static final String ERRORS_NON_RETRYABLE = "errors.non.retryable.count";

    // ===== Exception Rate by Classification =====

    public static final String EXCEPTION_DB_ERRORS = "exception.db.errors";
    public static final String EXCEPTION_NETWORK_ERRORS = "exception.network.errors";
    public static final String EXCEPTION_VALIDATION_ERRORS = "exception.validation.errors";
    public static final String EXCEPTION_DESERIALIZATION_ERRORS = "exception.deserialization.errors";
    public static final String EXCEPTION_UNKNOWN_ERRORS = "exception.unknown.errors";

    private MetricNames() {}
}
