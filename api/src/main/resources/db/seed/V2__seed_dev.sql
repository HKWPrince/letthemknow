-- Development seed. Applied only when Flyway locations include classpath:db/seed (local & test profiles).
--
-- Tenant : demo
-- Admin  : admin@demo.local / Admin123!
-- API key: ltk_demo1234_Kq7wL2xN9vB4mR6tY8uJ3hG5fD1sA0zC   (plaintext; only the SHA-256 hash is stored)

INSERT INTO tenants (name, status) VALUES ('demo', 'ACTIVE');

SET @demo_tenant_id = LAST_INSERT_ID();

INSERT INTO users (tenant_id, email, password_hash, role, status)
VALUES (@demo_tenant_id, 'admin@demo.local',
        '$2y$10$yAtbZtRreTj351ykL4gQw.8nwyZq8TXPTGG/ihfeeQX1wlK9Ot6s2',
        'ADMIN', 'ACTIVE');

INSERT INTO api_keys (tenant_id, name, api_key_prefix, api_key_hash, status)
VALUES (@demo_tenant_id, 'dev-key', 'demo1234',
        'b9b808be70e22334af6cb39aec749a6a64338133f5c5360c2112861f8f5d07ec',
        'ACTIVE');
