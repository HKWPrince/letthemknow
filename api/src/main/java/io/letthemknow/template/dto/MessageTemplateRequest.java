package io.letthemknow.template.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.channel.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * EMAIL: {@code subjectTemplate} required, payload {@code {"html": "...", "text": "..."}}.
 * LINE: payload {@code {"messages": [ ...1–5 LINE message objects... ]}}.
 */
public record MessageTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ChannelType channelType,
        @Size(max = 255) String subjectTemplate,
        @NotNull JsonNode contentPayload) {
}
