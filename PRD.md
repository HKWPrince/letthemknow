# Product Requirement Document (PRD): OmniNotify SaaS

## 1. System Architecture Overview
OmniNotify is a Multi-Tenant SaaS platform for campaign orchestration and automated notifications.

### 1.1 Core Components
* **API Gateway & Security**: Spring Boot 3.x with Spring Security. Dual authentication (JWT for Web, API Key for ERPs).
* **Multi-Tenancy Context**: Intercepts requests, extracts `tenant_id`, and injects it into `TenantContextHolder`.
* **Database**: **MS SQL Server 2022**. Logical isolation via `tenant_id`.
* **Cache & Message Broker**: Redis 7.x. Uses **Redisson DelayedQueue** for scheduled tasks and **Redis Stream** for high-throughput, chunked message dispatching.
* **Integrations**: LINE Messaging API (Flex/Narrowcast) & Dynamic SMTP (JavaMailSender).
* **Frontend**: React 18 + Vite + Tailwind CSS + shadcn/ui.

---

## 2. Database Schema (MS SQL Server - T-SQL)

```sql
-- 1. Tenants
CREATE TABLE tenants (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL,
    status NVARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET(),
    updated_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET()
);

-- 2. Users & Roles
CREATE TABLE users (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    email NVARCHAR(255) NOT NULL,
    password_hash NVARCHAR(255) NOT NULL,
    role NVARCHAR(30) NOT NULL DEFAULT 'OPERATOR',
    created_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT uq_tenant_email UNIQUE (tenant_id, email)
);

-- 3. External API Keys
CREATE TABLE api_keys (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    name NVARCHAR(100) NOT NULL,
    api_key_prefix NVARCHAR(10) NOT NULL,
    api_key_hash NVARCHAR(255) NOT NULL,
    status NVARCHAR(20) DEFAULT 'ACTIVE',
    created_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET()
);

-- 4. Channel Configurations (AES-256 Encrypted)
CREATE TABLE channel_configs (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    channel_type NVARCHAR(20) NOT NULL, -- 'LINE', 'EMAIL'
    
    line_channel_id NVARCHAR(100),
    line_channel_secret_enc NVARCHAR(MAX),
    line_channel_token_enc NVARCHAR(MAX),
    
    smtp_host NVARCHAR(255),
    smtp_port INT,
    smtp_username NVARCHAR(255),
    smtp_password_enc NVARCHAR(MAX),
    smtp_from_email NVARCHAR(255),
    smtp_from_name NVARCHAR(100),
    smtp_ssl_enabled BIT DEFAULT 1,
    
    created_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT uq_tenant_channel UNIQUE (tenant_id, channel_type)
);

-- 5. Message Templates
CREATE TABLE message_templates (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    name NVARCHAR(100) NOT NULL,
    channel_type NVARCHAR(20) NOT NULL, 
    template_type NVARCHAR(50) NOT NULL, 
    subject_template NVARCHAR(255), 
    content_payload NVARCHAR(MAX) NOT NULL CHECK (ISJSON(content_payload) = 1), 
    created_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET()
);

-- 6. Campaigns (Task Orchestration)
CREATE TABLE campaigns (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    title NVARCHAR(150) NOT NULL,
    channel_type NVARCHAR(20) NOT NULL,
    template_id BIGINT REFERENCES message_templates(id),
    status NVARCHAR(30) NOT NULL DEFAULT 'DRAFT', 
    scheduled_at DATETIMEOFFSET,
    target_audience_type NVARCHAR(50) NOT NULL, 
    target_audience_meta NVARCHAR(MAX) CHECK (ISJSON(target_audience_meta) = 1), 
    total_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    created_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET(),
    updated_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET()
);

-- 7. Campaign Recipients (Dispatch Queue Data)
CREATE TABLE campaign_recipients (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES campaigns(id),
    recipient_identifier NVARCHAR(255) NOT NULL, 
    payload_params NVARCHAR(MAX) CHECK (ISJSON(payload_params) = 1), 
    status NVARCHAR(20) NOT NULL DEFAULT 'PENDING', 
    error_code NVARCHAR(100),
    error_message NVARCHAR(MAX),
    retry_count INT DEFAULT 0,
    sent_at DATETIMEOFFSET
);
CREATE INDEX idx_recipient_camp_status ON campaign_recipients(campaign_id, status);