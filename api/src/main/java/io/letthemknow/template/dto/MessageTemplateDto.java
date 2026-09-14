package io.letthemknow.template.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.template.TemplateType;

import java.time.OffsetDateTime;

public record MessageTemplateDto(
        Long id,
        String name,
        ChannelType channelType,
        TemplateType templateType,
        String subjectTemplate,
        @JsonRawValue String contentPayload,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
