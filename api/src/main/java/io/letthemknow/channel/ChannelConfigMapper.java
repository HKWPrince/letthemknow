package io.letthemknow.channel;

import io.letthemknow.channel.dto.ChannelConfigDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ChannelConfigMapper {

    @Mapping(target = "hasLineChannelSecret", expression = "java(config.getLineChannelSecret() != null)")
    @Mapping(target = "hasLineChannelToken", expression = "java(config.getLineChannelToken() != null)")
    @Mapping(target = "hasSmtpPassword", expression = "java(config.getSmtpPassword() != null)")
    ChannelConfigDto toDto(ChannelConfig config);
}
