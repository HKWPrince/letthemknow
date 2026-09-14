package io.letthemknow.tenant.dto;

import io.letthemknow.tenant.TenantStatus;

import java.time.OffsetDateTime;

public record TenantDto(Long id, String name, TenantStatus status, OffsetDateTime createdAt) {
}
