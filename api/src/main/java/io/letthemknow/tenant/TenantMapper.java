package io.letthemknow.tenant;

import io.letthemknow.tenant.dto.TenantDto;
import org.mapstruct.Mapper;

@Mapper
public interface TenantMapper {

    TenantDto toDto(Tenant tenant);
}
