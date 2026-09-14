package io.letthemknow.dispatch;

import io.letthemknow.campaign.Campaign;
import io.letthemknow.campaign.CampaignRepository;
import io.letthemknow.campaign.CampaignRunner;
import io.letthemknow.campaign.CampaignStatus;
import io.letthemknow.common.tenant.SystemTenantScope;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes due campaign ids from the schedule queue and starts them (stale entries are ignored because
 * {@link CampaignRunner#start} verifies the status is still SCHEDULED). A periodic sweep also starts any
 * SCHEDULED campaign whose {@code scheduled_at} has passed, in case the Redis entry was lost.
 * Runs under {@link SystemTenantScope} because the tenant is only known once the campaign is loaded.
 */
@Component
@ConditionalOnProperty(prefix = "ltk.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulePoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SchedulePoller.class);

    private final DispatchStreams streams;
    private final CampaignRepository campaigns;
    private final CampaignRunner runner;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public SchedulePoller(DispatchStreams streams, CampaignRepository campaigns, CampaignRunner runner) {
        this.streams = streams;
        this.campaigns = campaigns;
        this.runner = runner;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, "ltk-schedule-poller");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void loop() {
        while (running.get()) {
            try {
                Long campaignId = streams.scheduleQueue().poll(2, TimeUnit.SECONDS);
                if (campaignId != null) {
                    startIfScheduled(campaignId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Schedule poller error: {}", e.toString());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${ltk.dispatch.schedule-sweep-ms:60000}", initialDelayString = "${ltk.dispatch.schedule-sweep-ms:60000}")
    public void sweepOverdue() {
        List<Campaign> due = SystemTenantScope.runAsSystem(() ->
                campaigns.findByStatusAndScheduledAtBefore(CampaignStatus.SCHEDULED, OffsetDateTime.now(ZoneOffset.UTC)));
        for (Campaign campaign : due) {
            log.info("Sweep starting overdue campaign {} (tenant {})", campaign.getId(), campaign.getTenantId());
            startIfScheduled(campaign.getId());
        }
    }

    void startIfScheduled(long campaignId) {
        Campaign campaign = SystemTenantScope.runAsSystem(() -> campaigns.findById(campaignId).orElse(null));
        if (campaign == null || campaign.getStatus() != CampaignStatus.SCHEDULED) {
            log.debug("Ignoring stale schedule entry for campaign {}", campaignId);
            return;
        }
        try {
            TenantContextHolder.runAs(campaign.getTenantId(), () -> runner.start(campaignId));
        } catch (RuntimeException e) {
            log.error("Scheduled start of campaign {} failed: {}", campaignId, e.getMessage());
        }
    }
}
