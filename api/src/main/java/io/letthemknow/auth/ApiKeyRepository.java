package io.letthemknow.auth;

import io.letthemknow.common.tenant.TenantScopedRepository;

import java.util.List;

public interface ApiKeyRepository extends TenantScopedRepository<ApiKey> {

    /** Authentication lookup before a tenant is known; call inside {@code SystemTenantScope}. */
    List<ApiKey> findByApiKeyPrefixAndStatus(String apiKeyPrefix, ApiKeyStatus status);

    List<ApiKey> findAllByOrderByCreatedAtDesc();
}
