package io.letthemknow.auth;

import io.letthemknow.auth.dto.ApiKeyDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ApiKeyMapper {

    @Mapping(target = "prefix", source = "apiKeyPrefix")
    ApiKeyDto toDto(ApiKey apiKey);
}
