package io.letthemknow.campaign.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.campaign.AudienceType;
import io.letthemknow.channel.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * {@code targetAudienceMeta} is required for LINE_AUDIENCE_GROUP: {@code {"audienceGroupId": 123}}.
 * {@code scheduledAt} is stored now and applied on publish.
 */
public record CampaignRequest(
        @NotBlank @Size(max = 150) String title,
        @NotNull ChannelType channelType,
        @NotNull Long templateId,
        @NotNull AudienceType targetAudienceType,
        JsonNode targetAudienceMeta,
        OffsetDateTime scheduledAt) {
}
