package io.letthemknow.campaign.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.letthemknow.campaign.RecipientStatus;

import java.time.OffsetDateTime;

public record CampaignRecipientDto(
        Long id,
        Long campaignId,
        String recipientIdentifier,
        @JsonRawValue String payloadParams,
        RecipientStatus status,
        String errorCode,
        String errorMessage,
        int retryCount,
        String externalMessageId,
        OffsetDateTime sentAt) {
}
