-- LetThemKnow V1 schema (MySQL 8).
--
-- Source: PRD.md §2 with the Phase 0 deltas from PLAN.md:
--   * campaign_recipients.tenant_id + index (tenant_id, campaign_id, status)
--   * campaigns.import_status / import_error
--   * campaign_recipients.status ∈ PENDING|SENDING|SENT|FAILED|CANCELLED
--   * new table transactional_messages
--   * api_keys.last_used_at
--   * CHECK constraints on users.status and tenants.status
--
-- Notes on the MySQL mapping (the PRD was written in T-SQL):
--   * DATETIMEOFFSET has no MySQL equivalent. Every timestamp is DATETIME(6) holding UTC; Hibernate is
--     configured with NORMALIZE_UTC so OffsetDateTime round-trips. TIMESTAMP is deliberately avoided —
--     its range ends in 2038 and it carries auto-update semantics we do not want.
--   * NVARCHAR becomes VARCHAR on a utf8mb4 schema, which is full Unicode including emoji and CJK.
--   * NVARCHAR(MAX) becomes LONGTEXT, and ISJSON(x)=1 becomes CHECK (JSON_VALID(x)), enforced since 8.0.16.
--     LONGTEXT rather than the native JSON type, so the entities keep mapping these columns as String.

-- 1. Tenants
CREATE TABLE tenants (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ux_tenants_name UNIQUE (name),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 2. Users
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(30)  NOT NULL DEFAULT 'ADMIN',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_role   CHECK (role IN ('ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3. External API keys (X-API-KEY: ltk_<8 char prefix>_<32 char random>; SHA-256 hash stored)
CREATE TABLE api_keys (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    name           VARCHAR(100) NOT NULL,
    api_key_prefix VARCHAR(10)  NOT NULL,
    api_key_hash   VARCHAR(255) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_used_at   DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at     DATETIME(6)  NULL,
    CONSTRAINT fk_api_keys_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_api_keys_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    INDEX ix_api_keys_prefix (api_key_prefix),
    INDEX ix_api_keys_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4. Channel configurations (secrets AES-256-GCM encrypted, format v1:<base64(iv||ct||tag)>)
CREATE TABLE channel_configs (
    id                      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id               BIGINT       NOT NULL,
    channel_type            VARCHAR(20)  NOT NULL,

    line_channel_id         VARCHAR(100) NULL,
    line_channel_secret_enc LONGTEXT     NULL,
    line_channel_token_enc  LONGTEXT     NULL,

    smtp_host               VARCHAR(255) NULL,
    smtp_port               INT          NULL,
    smtp_username           VARCHAR(255) NULL,
    smtp_password_enc       LONGTEXT     NULL,
    smtp_from_email         VARCHAR(255) NULL,
    smtp_from_name          VARCHAR(100) NULL,
    smtp_ssl_enabled        BIT(1)       NOT NULL DEFAULT b'1',

    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_tenant_channel UNIQUE (tenant_id, channel_type),
    CONSTRAINT fk_channel_configs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_channel_configs_type CHECK (channel_type IN ('LINE', 'EMAIL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5. Message templates (content_payload JSON: email {html,text}; LINE {messages:[...]} max 5)
CREATE TABLE message_templates (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL,
    name             VARCHAR(100) NOT NULL,
    channel_type     VARCHAR(20)  NOT NULL,
    template_type    VARCHAR(50)  NOT NULL,
    subject_template VARCHAR(255) NULL,
    content_payload  LONGTEXT     NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ux_message_templates_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_message_templates_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_message_templates_payload_json CHECK (JSON_VALID(content_payload)),
    CONSTRAINT ck_message_templates_type CHECK (channel_type IN ('LINE', 'EMAIL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 6. Campaigns
CREATE TABLE campaigns (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    title                VARCHAR(150) NOT NULL,
    channel_type         VARCHAR(20)  NOT NULL,
    template_id          BIGINT       NULL,
    status               VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    scheduled_at         DATETIME(6)  NULL,
    started_at           DATETIME(6)  NULL,
    finished_at          DATETIME(6)  NULL,
    target_audience_type VARCHAR(50)  NOT NULL,
    target_audience_meta LONGTEXT     NULL,
    total_count          INT          NOT NULL DEFAULT 0,
    success_count        INT          NOT NULL DEFAULT 0,
    failed_count         INT          NOT NULL DEFAULT 0,
    import_status        VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    import_error         LONGTEXT     NULL,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_campaigns_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_campaigns_template FOREIGN KEY (template_id) REFERENCES message_templates(id),
    CONSTRAINT ck_campaigns_meta_json     CHECK (target_audience_meta IS NULL OR JSON_VALID(target_audience_meta)),
    CONSTRAINT ck_campaigns_type          CHECK (channel_type IN ('LINE', 'EMAIL')),
    CONSTRAINT ck_campaigns_status        CHECK (status IN ('DRAFT', 'SCHEDULED', 'PROCESSING', 'COMPLETED',
                                                            'AWAITING_RESOLUTION', 'RETRYING', 'TERMINATED')),
    CONSTRAINT ck_campaigns_audience_type CHECK (target_audience_type IN ('CSV_LIST', 'LINE_AUDIENCE_GROUP')),
    CONSTRAINT ck_campaigns_import_status CHECK (import_status IN ('NONE', 'IMPORTING', 'READY', 'FAILED')),
    INDEX ix_campaigns_tenant_status (tenant_id, status),
    INDEX ix_campaigns_tenant_created (tenant_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 7. Campaign recipients (dispatch queue data; tenant_id denormalised for filter isolation)
CREATE TABLE campaign_recipients (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    campaign_id          BIGINT       NOT NULL,
    recipient_identifier VARCHAR(255) NOT NULL,
    payload_params       LONGTEXT     NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_code           VARCHAR(100) NULL,
    error_message        LONGTEXT     NULL,
    retry_count          INT          NOT NULL DEFAULT 0,
    external_message_id  VARCHAR(255) NULL,
    sent_at              DATETIME(6)  NULL,
    CONSTRAINT fk_campaign_recipients_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_campaign_recipients_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT ck_campaign_recipients_params_json CHECK (payload_params IS NULL OR JSON_VALID(payload_params)),
    CONSTRAINT ck_campaign_recipients_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT ux_recipients_campaign_identifier UNIQUE (campaign_id, recipient_identifier),
    INDEX ix_recipients_tenant_campaign_status (tenant_id, campaign_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 8. Transactional (single push) messages — no campaign row
CREATE TABLE transactional_messages (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    channel_type         VARCHAR(20)  NOT NULL,
    template_id          BIGINT       NOT NULL,
    recipient_identifier VARCHAR(255) NOT NULL,
    payload_params       LONGTEXT     NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_code           VARCHAR(100) NULL,
    error_message        LONGTEXT     NULL,
    external_message_id  VARCHAR(255) NULL,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at              DATETIME(6)  NULL,
    CONSTRAINT fk_transactional_messages_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_transactional_messages_template FOREIGN KEY (template_id) REFERENCES message_templates(id),
    CONSTRAINT ck_transactional_messages_params_json CHECK (payload_params IS NULL OR JSON_VALID(payload_params)),
    CONSTRAINT ck_transactional_messages_type   CHECK (channel_type IN ('LINE', 'EMAIL')),
    CONSTRAINT ck_transactional_messages_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    INDEX ix_transactional_messages_tenant_created (tenant_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
