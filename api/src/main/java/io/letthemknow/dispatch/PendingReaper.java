package io.letthemknow.dispatch;

import io.letthemknow.channel.ChannelType;
import org.redisson.api.AutoClaimResult;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Every minute, XAUTOCLAIMs entries idle longer than {@code ltk.dispatch.reaper-idle} (default 5 min)
 * from dead consumers and re-processes them with {@code reclaimStale = true}.
 */
@Component
@ConditionalOnProperty(prefix = "ltk.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingReaper {

    private static final Logger log = LoggerFactory.getLogger(PendingReaper.class);
    private static final int BATCH = 50;

    private final DispatchStreams streams;
    private final ChunkProcessor processor;
    private final DispatchProperties props;

    public PendingReaper(DispatchStreams streams, ChunkProcessor processor, DispatchProperties props) {
        this.streams = streams;
        this.processor = processor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${ltk.dispatch.reaper-interval-ms:60000}", initialDelayString = "${ltk.dispatch.reaper-interval-ms:60000}")
    public void reapScheduled() {
        reap();
    }

    /** @return number of entries recovered */
    public int reap() {
        int recovered = 0;
        String consumer = streams.consumerName(0) + "-reaper";
        for (ChannelType channel : ChannelType.values()) {
            RStream<String, String> stream = streams.stream(channel);
            try {
                AutoClaimResult<String, String> result = stream.autoClaim(DispatchStreams.GROUP, consumer,
                        props.reaperIdle().toMillis(), TimeUnit.MILLISECONDS, StreamMessageId.MIN, BATCH);
                if (result == null || result.getMessages() == null) {
                    continue;
                }
                for (Map.Entry<StreamMessageId, Map<String, String>> entry : result.getMessages().entrySet()) {
                    recovered += recover(stream, entry.getKey(), entry.getValue()) ? 1 : 0;
                }
            } catch (Exception e) {
                log.error("Reaper failed on {}: {}", streams.streamKey(channel), e.toString());
            }
        }
        if (recovered > 0) {
            log.info("Reaper recovered {} stale chunk(s)", recovered);
        }
        return recovered;
    }

    private boolean recover(RStream<String, String> stream, StreamMessageId id, Map<String, String> fields) {
        DispatchMessage message;
        try {
            message = streams.decode(fields);
        } catch (RuntimeException e) {
            log.error("Reaper discarding malformed entry {}: {}", id, e.getMessage());
            stream.ack(DispatchStreams.GROUP, id);
            return false;
        }
        try {
            processor.process(message, true);
            stream.ack(DispatchStreams.GROUP, id);
            return true;
        } catch (RuntimeException e) {
            log.error("Reaper could not process entry {} of campaign {}: {}", id, message.campaignId(), e.toString());
            return false;
        }
    }
}
