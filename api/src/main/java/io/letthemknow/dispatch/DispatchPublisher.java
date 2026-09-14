package io.letthemknow.dispatch;

import io.letthemknow.channel.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Splits recipient ids into channel-sized chunks and writes them to the stream / retry queue. */
@Component
public class DispatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(DispatchPublisher.class);

    private final DispatchStreams streams;
    private final DispatchProperties props;

    public DispatchPublisher(DispatchStreams streams, DispatchProperties props) {
        this.streams = streams;
        this.props = props;
    }

    public int chunkSize(ChannelType channel) {
        return channel == ChannelType.EMAIL ? props.emailChunkSize() : props.lineChunkSize();
    }

    /** @return number of chunks enqueued */
    public int enqueueChunks(long tenantId, long campaignId, ChannelType channel, List<Long> recipientIds) {
        int size = chunkSize(channel);
        int chunks = 0;
        for (int from = 0; from < recipientIds.size(); from += size) {
            List<Long> ids = new ArrayList<>(recipientIds.subList(from, Math.min(from + size, recipientIds.size())));
            streams.add(new DispatchMessage(tenantId, campaignId, channel, ids, 0));
            chunks++;
        }
        log.info("Enqueued {} chunk(s) / {} recipient(s) for campaign {} on {}", chunks, recipientIds.size(),
                campaignId, streams.streamKey(channel));
        return chunks;
    }

    /** Re-enqueues a chunk after {@code delay} through the retry delayed queue. */
    public void enqueueRetry(DispatchMessage message, Duration delay) {
        streams.retryDelayedQueue().offer(streams.codec().encode(message), delay.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Scheduled retry attempt {} for {} recipient(s) of campaign {} in {}", message.attempt(),
                message.recipientIds().size(), message.campaignId(), delay);
    }
}
