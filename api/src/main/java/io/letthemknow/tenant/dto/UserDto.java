package io.letthemknow.tenant.dto;

import io.letthemknow.tenant.UserRole;
import io.letthemknow.tenant.UserStatus;

import java.time.OffsetDateTime;

public record UserDto(Long id, Long tenantId, String email, UserRole role, UserStatus status, OffsetDateTime createdAt) {
}
