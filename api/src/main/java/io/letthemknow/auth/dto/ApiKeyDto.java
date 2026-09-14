package io.letthemknow.auth.dto;

import io.letthemknow.auth.ApiKeyStatus;

import java.time.OffsetDateTime;

/** Never contains the key or its hash; {@code prefix} is enough to identify it. */
public record ApiKeyDto(
        Long id,
        String name,
        String prefix,
        ApiKeyStatus status,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime revokedAt) {
}
