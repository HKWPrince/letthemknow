package io.letthemknow.campaign.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.letthemknow.campaign.AudienceType;
import io.letthemknow.campaign.CampaignStatus;
import io.letthemknow.campaign.ImportStatus;
import io.letthemknow.channel.ChannelType;

import java.time.OffsetDateTime;

public record CampaignDto(
        Long id,
        String title,
        ChannelType channelType,
        Long templateId,
        CampaignStatus status,
        OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        AudienceType targetAudienceType,
        @JsonRawValue String targetAudienceMeta,
        int totalCount,
        int successCount,
        int failedCount,
        ImportStatus importStatus,
        String importError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
