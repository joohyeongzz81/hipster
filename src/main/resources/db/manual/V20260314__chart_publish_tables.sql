CREATE TABLE IF NOT EXISTS chart_publish_state (
    chart_name VARCHAR(100) NOT NULL PRIMARY KEY,
    current_version VARCHAR(64) NULL,
    previous_version VARCHAR(64) NULL,
    candidate_version VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    mysql_projection_ref VARCHAR(128) NULL,
    previous_mysql_projection_ref VARCHAR(128) NULL,
    candidate_mysql_projection_ref VARCHAR(128) NULL,
    es_index_ref VARCHAR(128) NULL,
    previous_es_index_ref VARCHAR(128) NULL,
    candidate_es_index_ref VARCHAR(128) NULL,
    logical_as_of_at DATETIME(6) NULL,
    previous_logical_as_of_at DATETIME(6) NULL,
    candidate_logical_as_of_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_validation_status VARCHAR(32) NOT NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(1000) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS chart_publish_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chart_name VARCHAR(100) NOT NULL,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    row_count_mysql BIGINT NULL,
    doc_count_es BIGINT NULL,
    validation_summary_json TEXT NULL,
    source_snapshot_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    rolled_back_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_chart_publish_history_chart_version (chart_name, version, created_at)
);
