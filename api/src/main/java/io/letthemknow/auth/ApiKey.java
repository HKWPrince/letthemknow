package io.letthemknow.auth;

import io.letthemknow.common.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "api_keys")
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = TenantAwareEntity.TENANT_FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey extends TenantAwareEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "api_key_prefix", nullable = false, length = 10)
    private String apiKeyPrefix;

    /** SHA-256 hex of the full key; never the key itself. */
    @Column(name = "api_key_hash", nullable = false, length = 255)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    public ApiKey(String name, String apiKeyPrefix, String apiKeyHash) {
        this.name = name;
        this.apiKeyPrefix = apiKeyPrefix;
        this.apiKeyHash = apiKeyHash;
    }

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public boolean isActive() {
        return status == ApiKeyStatus.ACTIVE;
    }

    public void revoke() {
        status = ApiKeyStatus.REVOKED;
        revokedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
