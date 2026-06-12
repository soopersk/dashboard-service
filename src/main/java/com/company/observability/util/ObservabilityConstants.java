package com.company.observability.util;

/**
 * Single source of truth for all metric names.
 * Convention: obs.{layer}.{entity}.{metric}
 */
public final class ObservabilityConstants {

    private ObservabilityConstants() {}

    // ================================================================
    // API layer (controllers) — timers for endpoint latency
    // ================================================================
    public static final String API_INGESTION_DURATION = "obs.api.ingestion.duration";
    public static final String API_QUERY_DURATION = "obs.api.query.duration";
    public static final String API_ANALYTICS_DURATION = "obs.api.analytics.duration";
    public static final String API_ERROR = "obs.api.error";

    // ================================================================
    // Ingestion layer (RunIngestionService)
    // ================================================================
    public static final String INGESTION_RUN_STARTED = "obs.ingestion.run.started";
    public static final String INGESTION_RUN_COMPLETED = "obs.ingestion.run.completed";
    public static final String INGESTION_RUN_DUPLICATE = "obs.ingestion.run.duplicate";
    public static final String INGESTION_RUN_ACTIVE = "obs.ingestion.run.active";

    // ================================================================
    // Query layer (RunQueryService)
    // ================================================================
    public static final String QUERY_STATUS_CACHE_HIT = "obs.query.status.cache.hit";
    public static final String QUERY_STATUS_CACHE_MISS = "obs.query.status.cache.miss";
    public static final String QUERY_BATCH_PROCESSED = "obs.query.batch.processed";
    public static final String QUERY_BATCH_DURATION = "obs.query.batch.duration";
    public static final String QUERY_BATCH_SIZE = "obs.query.batch.size";

    // ================================================================
    // SLA layer
    // ================================================================
    public static final String SLA_BREACH_CREATED = "obs.sla.breach.created";
    public static final String SLA_BREACH_DUPLICATE = "obs.sla.breach.duplicate";
    public static final String SLA_ALERT_SENT = "obs.sla.alert.sent";
    public static final String SLA_ALERT_FAILED = "obs.sla.alert.failed";
    public static final String SLA_BREACH_LIVE_DETECTED = "obs.sla.breach.live.detected";
    public static final String SLA_BREACH_CLEARED = "obs.sla.breach.cleared";
    public static final String SLA_DETECTION_FAILURE = "obs.sla.detection.failure";
    public static final String SLA_DETECTION_EXECUTION = "obs.sla.detection.execution";
    public static final String SLA_DETECTION_DURATION = "obs.sla.detection.duration";
    public static final String SLA_APPROACHING_COUNT = "obs.sla.approaching.count";
    public static final String SLA_DETECTION_LAST_BREACHES = "obs.sla.detection.last.breaches";
    public static final String SLA_MONITORING_ACTIVE = "obs.sla.monitoring.active";
    public static final String SLA_EVALUATION_DURATION = "obs.sla.evaluation.duration";

    // ================================================================
    // Cache layer (Redis)
    // ================================================================
    public static final String CACHE_REDIS_DURATION = "obs.cache.redis.duration";
    public static final String CACHE_ANALYTICS_HIT = "obs.cache.analytics.hit";
    public static final String CACHE_ANALYTICS_MISS = "obs.cache.analytics.miss";
    public static final String CACHE_ANALYTICS_EVICTION = "obs.cache.analytics.eviction";
    public static final String CACHE_STATE_HIT = "obs.cache.state.hit";
    public static final String CACHE_STATE_MISS = "obs.cache.state.miss";

    // ================================================================
    // DB layer (repositories)
    // ================================================================
    public static final String DB_QUERY_DURATION = "obs.db.query.duration";

    // ================================================================
    // Partition layer (jobs)
    // ================================================================
    public static final String PARTITION_CREATE_SUCCESS = "obs.partition.create.success";
    public static final String PARTITION_CREATE_FAILURE = "obs.partition.create.failure";
    public static final String PARTITION_DROP_SUCCESS = "obs.partition.drop.success";
    public static final String PARTITION_DROP_FAILURE = "obs.partition.drop.failure";
    public static final String PARTITION_MONITOR_FAILURE = "obs.partition.monitor.failure";
    public static final String PARTITION_ROWS_TOTAL = "obs.partition.rows.total";
    public static final String PARTITION_ROWS_DAILY = "obs.partition.rows.daily";
    public static final String PARTITION_ROWS_MONTHLY = "obs.partition.rows.monthly";
    public static final String PARTITION_COUNT = "obs.partition.count";
}
