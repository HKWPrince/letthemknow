package io.letthemknow.integration;

import io.letthemknow.integration.dto.TransactionalMessageDto;
import org.mapstruct.Mapper;

@Mapper
public interface TransactionalMessageMapper {

    TransactionalMessageDto toDto(TransactionalMessage message);
}
