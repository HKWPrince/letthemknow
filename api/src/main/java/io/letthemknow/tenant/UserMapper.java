package io.letthemknow.tenant;

import io.letthemknow.tenant.dto.UserDto;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    UserDto toDto(User user);
}
