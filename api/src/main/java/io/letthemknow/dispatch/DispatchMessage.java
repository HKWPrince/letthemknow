package io.letthemknow.dispatch;

import io.letthemknow.channel.ChannelType;

import java.util.List;

/** One stream entry: a chunk of recipient ids of one campaign. {@code attempt} is zero-based. */
public record DispatchMessage(long tenantId, long campaignId, ChannelType channel, List<Long> recipientIds, int attempt) {

    public DispatchMessage retry(List<Long> ids) {
        return new DispatchMessage(tenantId, campaignId, channel, ids, attempt + 1);
    }
}
