package io.letthemknow.dispatch;

import io.letthemknow.campaign.AudienceType;
import io.letthemknow.campaign.Campaign;
import io.letthemknow.campaign.CampaignRepository;
import io.letthemknow.campaign.CampaignStatus;
import io.letthemknow.channel.line.LineMessages;
import io.letthemknow.channel.line.LineSender;
import io.letthemknow.common.tenant.SystemTenantScope;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Every 30s polls LINE narrowcast progress for audience-group campaigns still processing. */
@Component
@ConditionalOnProperty(prefix = "ltk.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NarrowcastProgressPoller {

    private static final Logger log = LoggerFactory.getLogger(NarrowcastProgressPoller.class);
    private static final List<CampaignStatus> PROCESSING = List.of(CampaignStatus.PROCESSING, CampaignStatus.RETRYING);

    private final CampaignRepository campaigns;
    private final NarrowcastService narrowcast;
    private final LineSender lineSender;

    public NarrowcastProgressPoller(CampaignRepository campaigns, NarrowcastService narrowcast, LineSender lineSender) {
        this.campaigns = campaigns;
        this.narrowcast = narrowcast;
        this.lineSender = lineSender;
    }

    @Scheduled(fixedDelayString = "${ltk.dispatch.narrowcast-poll-ms:30000}", initialDelayString = "${ltk.dispatch.narrowcast-poll-ms:30000}")
    public void pollScheduled() {
        poll();
    }

    /** @return number of campaigns finalised in this pass */
    public int poll() {
        List<Campaign> active = SystemTenantScope.runAsSystem(() ->
                campaigns.findByStatusInAndTargetAudienceType(PROCESSING, AudienceType.LINE_AUDIENCE_GROUP));
        int finalised = 0;
        for (Campaign campaign : active) {
            String requestId = narrowcast.meta(campaign).path(NarrowcastService.META_REQUEST_ID).asText(null);
            if (requestId == null || requestId.isBlank()) {
                continue;
            }
            try {
                boolean done = TenantContextHolder.runAs(campaign.getTenantId(), () -> {
                    LineMessages.NarrowcastProgress progress = lineSender.progress(campaign.getTenantId(), requestId);
                    if (progress == null || !progress.isDone()) {
                        return false;
                    }
                    narrowcast.applyProgress(campaign.getId(), progress);
                    return true;
                });
                if (done) {
                    finalised++;
                }
            } catch (RuntimeException e) {
                log.warn("Narrowcast progress check failed for campaign {}: {}", campaign.getId(), e.toString());
            }
        }
        return finalised;
    }
}
