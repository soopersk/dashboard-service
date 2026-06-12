CREATE TABLE IF NOT EXISTS sla_breach_events (
    breach_id       BIGSERIAL       PRIMARY KEY,
    run_id          VARCHAR(100)    NOT NULL UNIQUE,
    calculator_id   VARCHAR(100)    NOT NULL,
    calculator_name VARCHAR(255)    NOT NULL,
    tenant_id       VARCHAR(50),
    reporting_date  DATE,
    breach_type     VARCHAR(50)     NOT NULL,
    expected_value  BIGINT,
    actual_value    BIGINT,
    alerted         BOOLEAN         DEFAULT false,
    alerted_at      TIMESTAMPTZ,
    alert_status    VARCHAR(20)     DEFAULT 'PENDING'
                    CHECK (alert_status IN ('PENDING', 'SENT', 'FAILED', 'RETRYING')),
    retry_count     INT             DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
