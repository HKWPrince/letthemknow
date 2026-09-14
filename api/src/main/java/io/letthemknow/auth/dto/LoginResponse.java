package io.letthemknow.auth.dto;

import io.letthemknow.tenant.dto.TenantDto;
import io.letthemknow.tenant.dto.UserDto;

import java.time.OffsetDateTime;

public record LoginResponse(String token, OffsetDateTime expiresAt, UserDto user, TenantDto tenant) {
}
