CREATE TABLE batch_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    endpoint_url VARCHAR(1000) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_definition_code (code)
);

CREATE TABLE batch_subscription (
    id BIGINT NOT NULL AUTO_INCREMENT,

    customer_id BIGINT NOT NULL,

    batch_definition_id BIGINT NOT NULL,

    cron_expression VARCHAR(100) NOT NULL,

    timezone VARCHAR(100) NOT NULL DEFAULT 'Europe/Rome',

    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    last_run_at DATETIME NULL,

    next_run_at DATETIME NULL,

    params_json JSON NULL,

    body_json JSON NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_batch_subscription_definition
        FOREIGN KEY (batch_definition_id)
        REFERENCES batch_definition(id),

    INDEX idx_batch_subscription_next_run
        (enabled, next_run_at)
);

CREATE TABLE batch_execution (
    id BIGINT NOT NULL AUTO_INCREMENT,

    batch_subscription_id BIGINT NOT NULL,

    status VARCHAR(20) NOT NULL,

    started_at DATETIME NOT NULL,

    ended_at DATETIME NULL,

    response_code INT NULL,

    error_message TEXT NULL,

    response_body LONGTEXT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_batch_execution_subscription
        FOREIGN KEY (batch_subscription_id)
        REFERENCES batch_subscription(id),

    INDEX idx_batch_execution_subscription
        (batch_subscription_id)
);