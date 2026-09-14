package io.letthemknow.campaign.dto;

import java.time.OffsetDateTime;

/** Optional body of {@code POST /campaigns/{id}/publish}: absent or past {@code scheduledAt} starts now. */
public record PublishRequest(OffsetDateTime scheduledAt) {
}
