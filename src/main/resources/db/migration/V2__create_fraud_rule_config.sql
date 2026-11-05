-- Single-row table holding the runtime-editable fraud rule parameters (id is always 1).
CREATE TABLE fraud_rule_config (
    id                      INTEGER PRIMARY KEY,
    window_seconds          INTEGER        NOT NULL,
    max_transactions        INTEGER        NOT NULL,
    min_distinct_locations  INTEGER        NOT NULL,
    high_value_amount       NUMERIC(19, 4) NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by              VARCHAR(128)
);
