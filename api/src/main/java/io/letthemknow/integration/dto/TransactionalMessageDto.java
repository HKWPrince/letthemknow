package io.letthemknow.integration.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.integration.TransactionalStatus;

import java.time.OffsetDateTime;

public record TransactionalMessageDto(
        Long id,
        ChannelType channelType,
        Long templateId,
        String recipientIdentifier,
        @JsonRawValue String payloadParams,
        TransactionalStatus status,
        String errorCode,
        String errorMessage,
        String externalMessageId,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt) {
}
