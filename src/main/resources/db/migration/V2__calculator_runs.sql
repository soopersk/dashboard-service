CREATE TABLE IF NOT EXISTS calculator_runs (
    run_id               VARCHAR(100)   NOT NULL,

    -- Calculator metadata
    calculator_id        VARCHAR(100)   NOT NULL,
    calculator_name      VARCHAR(255)   NOT NULL,
    tenant_id            VARCHAR(50),
    frequency            VARCHAR(20)    NOT NULL,

    -- Partition key
    reporting_date       DATE           NOT NULL,

    -- Timing
    start_time           TIMESTAMPTZ    NOT NULL,
    end_time             TIMESTAMPTZ,
    duration_ms          BIGINT,

    -- Status
    status               VARCHAR(20)    NOT NULL,

    -- SLA tracking
    sla_time             TIMESTAMPTZ,
    expected_duration_ms BIGINT,
    estimated_start_time TIMESTAMPTZ,
    estimated_end_time   TIMESTAMPTZ,

    -- SLA breach
    sla_band             VARCHAR(20)    CHECK (sla_band IS NULL OR sla_band IN ('ON_TIME','LATE','VERY_LATE')),
    sla_breached         BOOLEAN        DEFAULT false,
    sla_breach_reason    TEXT,

    -- Promoted from run_parameters JSONB (structural fields used in SQL predicates)
    run_number           VARCHAR(10),
    run_type             VARCHAR(20),
    region               VARCHAR(20),

    -- Remaining dynamic metadata (JSONB)
    run_parameters       JSONB,
    additional_attributes JSONB,

    -- Audit
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    PRIMARY KEY (run_id, reporting_date)
) PARTITION BY RANGE (reporting_date);

-- ── Partition lifecycle functions ─────────────────────────────────────────────

CREATE OR REPLACE FUNCTION create_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    start_date     DATE;
    end_date       DATE;
    partition_date DATE;
    partition_name TEXT;
    partition_exists BOOLEAN;
BEGIN
    start_date := CURRENT_DATE - INTERVAL '1 day';
    end_date   := CURRENT_DATE + INTERVAL '60 days';
    partition_date := start_date;

    WHILE partition_date <= end_date LOOP
        partition_name := 'calculator_runs_' || TO_CHAR(partition_date, 'YYYY_MM_DD');

        SELECT EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = partition_name AND n.nspname = 'public'
        ) INTO partition_exists;

        IF NOT partition_exists THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF calculator_runs
                FOR VALUES FROM (%L) TO (%L)',
                partition_name, partition_date, partition_date + INTERVAL '1 day'
            );
            RAISE NOTICE 'Created partition: %', partition_name;
        END IF;

        partition_date := partition_date + INTERVAL '1 day';
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION drop_old_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    partition_record RECORD;
    cutoff_date      DATE;
BEGIN
    cutoff_date := CURRENT_DATE - INTERVAL '395 days';

    FOR partition_record IN
        SELECT c.relname AS partition_name
        FROM pg_class c
        JOIN pg_inherits i ON c.oid = i.inhrelid
        JOIN pg_class p ON i.inhparent = p.oid
        WHERE p.relname = 'calculator_runs'
          AND c.relname LIKE 'calculator_runs_%'
    LOOP
        DECLARE
            partition_date DATE;
            date_string    TEXT;
        BEGIN
            date_string    := SUBSTRING(partition_record.partition_name FROM 'calculator_runs_(.*)');
            partition_date := TO_DATE(REPLACE(date_string, '_', '-'), 'YYYY-MM-DD');

            IF partition_date < cutoff_date THEN
                EXECUTE format('DROP TABLE IF EXISTS %I', partition_record.partition_name);
                RAISE NOTICE 'Dropped old partition: %', partition_record.partition_name;
            END IF;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE WARNING 'Failed to process partition %: %',
                    partition_record.partition_name, SQLERRM;
        END;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION get_partition_statistics()
RETURNS TABLE (
    partition_name TEXT,
    partition_date DATE,
    row_count      BIGINT,
    total_size     TEXT,
    daily_runs     BIGINT,
    monthly_runs   BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        c.relname::TEXT,
        TO_DATE(REPLACE(SUBSTRING(c.relname FROM 'calculator_runs_(.*)'), '_', '-'), 'YYYY-MM-DD'),
        pg_class_size.reltuples::BIGINT,
        pg_size_pretty(pg_total_relation_size(c.oid)),
        (SELECT COUNT(*) FROM ONLY calculator_runs p
         WHERE p.tableoid = c.oid AND p.frequency = 'DAILY')::BIGINT,
        (SELECT COUNT(*) FROM ONLY calculator_runs p
         WHERE p.tableoid = c.oid AND p.frequency = 'MONTHLY')::BIGINT
    FROM pg_class c
    JOIN pg_inherits i ON c.oid = i.inhrelid
    JOIN pg_class parent ON i.inhparent = parent.oid
    LEFT JOIN LATERAL (
        SELECT reltuples FROM pg_class WHERE oid = c.oid
    ) pg_class_size ON true
    WHERE parent.relname = 'calculator_runs'
      AND c.relname LIKE 'calculator_runs_%'
    ORDER BY c.relname DESC;
END;
$$ LANGUAGE plpgsql;

-- Create initial partitions (yesterday → +60 days)
SELECT create_calculator_run_partitions();

COMMENT ON FUNCTION create_calculator_run_partitions()    IS 'Call daily to create partitions for next 60 days';
COMMENT ON FUNCTION drop_old_calculator_run_partitions()  IS 'Call weekly to drop partitions older than retention period';
