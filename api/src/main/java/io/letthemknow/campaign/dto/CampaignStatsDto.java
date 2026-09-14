package io.letthemknow.campaign.dto;

import io.letthemknow.campaign.CampaignStatus;
import io.letthemknow.campaign.ImportStatus;

import java.time.OffsetDateTime;
import java.util.List;

/** Live progress of one campaign; polled by the console while it is processing. */
public record CampaignStatsDto(
        Long campaignId,
        CampaignStatus status,
        boolean processing,
        ImportStatus importStatus,
        int totalCount,
        int successCount,
        int failedCount,
        RecipientCounts recipients,
        List<FailureBreakdownDto> failureBreakdown,
        OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime updatedAt) {
}
