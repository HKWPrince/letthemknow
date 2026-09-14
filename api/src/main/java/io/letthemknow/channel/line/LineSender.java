package io.letthemknow.channel.line;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.channel.ChannelConfigRepository;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.channel.line.LineMessages.MulticastRequest;
import io.letthemknow.channel.line.LineMessages.NarrowcastProgress;
import io.letthemknow.channel.line.LineMessages.NarrowcastRequest;
import io.letthemknow.channel.line.LineMessages.PushRequest;
import io.letthemknow.channel.line.LineMessages.Recipient;
import io.letthemknow.channel.line.LineMessages.SendResult;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tenant-aware facade over {@link LineApiClient}: resolves the decrypted channel token per call. */
@Component
public class LineSender {

    public static final int MULTICAST_MAX = 500;

    private final LineApiClient client;
    private final ChannelConfigRepository configRepository;

    public LineSender(LineApiClient client, ChannelConfigRepository configRepository) {
        this.client = client;
        this.configRepository = configRepository;
    }

    public SendResult push(long tenantId, String userId, List<JsonNode> messages, String retryKey) {
        return client.push(token(tenantId), new PushRequest(userId, messages), retryKey);
    }

    public SendResult multicast(long tenantId, List<String> userIds, List<JsonNode> messages, String retryKey) {
        if (userIds.size() > MULTICAST_MAX) {
            throw new IllegalArgumentException("multicast accepts at most " + MULTICAST_MAX + " recipients");
        }
        return client.multicast(token(tenantId), new MulticastRequest(userIds, messages), retryKey);
    }

    public SendResult narrowcast(long tenantId, long audienceGroupId, List<JsonNode> messages, String retryKey) {
        return client.narrowcast(token(tenantId),
                new NarrowcastRequest(messages, Recipient.audience(audienceGroupId)), retryKey);
    }

    public NarrowcastProgress progress(long tenantId, String requestId) {
        return client.narrowcastProgress(token(tenantId), requestId);
    }

    private String token(long tenantId) {
        return TenantContextHolder.runAs(tenantId, () -> configRepository.findByChannelType(ChannelType.LINE)
                .map(c -> c.getLineChannelToken())
                .filter(t -> t != null && !t.isBlank())
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE,
                        "LINE channel is not configured for this tenant")));
    }
}
