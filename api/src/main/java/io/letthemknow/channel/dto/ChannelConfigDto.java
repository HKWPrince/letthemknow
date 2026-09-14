package io.letthemknow.channel.dto;

import io.letthemknow.channel.ChannelType;

import java.time.OffsetDateTime;

/** Secrets are never returned; {@code has*} flags say whether one is stored. */
public record ChannelConfigDto(
        Long id,
        ChannelType channelType,
        String lineChannelId,
        boolean hasLineChannelSecret,
        boolean hasLineChannelToken,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        boolean hasSmtpPassword,
        String smtpFromEmail,
        String smtpFromName,
        boolean smtpSslEnabled,
        OffsetDateTime updatedAt) {
}
