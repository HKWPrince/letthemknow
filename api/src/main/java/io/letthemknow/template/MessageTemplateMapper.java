package io.letthemknow.template;

import io.letthemknow.template.dto.MessageTemplateDto;
import org.mapstruct.Mapper;

@Mapper
public interface MessageTemplateMapper {

    MessageTemplateDto toDto(MessageTemplate template);
}
