package io.letthemknow.auth.dto;

import io.letthemknow.tenant.dto.TenantDto;
import io.letthemknow.tenant.dto.UserDto;

public record MeResponse(UserDto user, TenantDto tenant) {
}
