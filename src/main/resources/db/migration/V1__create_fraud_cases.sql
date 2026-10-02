CREATE TABLE fraud_cases (
    id                UUID PRIMARY KEY,
    alert_id          VARCHAR(64)  NOT NULL UNIQUE,
    account_id        VARCHAR(64)  NOT NULL,
    rule              VARCHAR(64)  NOT NULL,
    severity          VARCHAR(16)  NOT NULL,
    score             DOUBLE PRECISION NOT NULL,
    reason            VARCHAR(2000) NOT NULL,
    transaction_count INTEGER      NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    assignee          VARCHAR(128),
    notes             VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE  NOT NULL
);
CREATE INDEX idx_fraud_cases_status_created ON fraud_cases (status, created_at DESC);
CREATE INDEX idx_fraud_cases_account ON fraud_cases (account_id);
