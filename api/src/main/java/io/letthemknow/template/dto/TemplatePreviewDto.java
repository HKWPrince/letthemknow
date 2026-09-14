package io.letthemknow.template.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.channel.ChannelType;

import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplatePreviewDto(
        ChannelType channelType,
        Set<String> placeholders,
        String subject,
        String html,
        String text,
        List<JsonNode> messages) {
}
