package io.letthemknow.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

/** Delayed-queue side of scheduling: {@code ltk:campaign:schedule} holds campaign ids due to start. */
@Component
public class CampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduler.class);

    private final DispatchStreams streams;

    public CampaignScheduler(DispatchStreams streams) {
        this.streams = streams;
    }

    public void schedule(long campaignId, OffsetDateTime at) {
        Duration delay = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), at);
        long millis = Math.max(0, delay.toMillis());
        streams.scheduleDelayedQueue().offer(campaignId, millis, TimeUnit.MILLISECONDS);
        log.info("Campaign {} scheduled to start in {} ms", campaignId, millis);
    }

    public void cancel(long campaignId) {
        boolean removed = streams.scheduleDelayedQueue().remove(campaignId);
        log.info("Campaign {} schedule entry {}", campaignId, removed ? "removed" : "not found (already due or never queued)");
    }
}
