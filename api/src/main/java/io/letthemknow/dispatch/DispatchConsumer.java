package io.letthemknow.dispatch;

import io.letthemknow.channel.ChannelType;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * One blocking XREADGROUP loop on one stream. Entries are ACKed only after {@link ChunkProcessor}
 * has written every recipient status; on unexpected failure the entry stays pending for the reaper.
 */
class DispatchConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumer.class);

    private final ChannelType channel;
    private final RStream<String, String> stream;
    private final String consumerName;
    private final DispatchStreams streams;
    private final ChunkProcessor processor;
    private final DispatchProperties props;
    private final BooleanSupplier running;
    private final BooleanSupplier paused;

    DispatchConsumer(ChannelType channel, String consumerName, DispatchStreams streams, ChunkProcessor processor,
                     DispatchProperties props, BooleanSupplier running, BooleanSupplier paused) {
        this.channel = channel;
        this.stream = streams.stream(channel);
        this.consumerName = consumerName;
        this.streams = streams;
        this.processor = processor;
        this.props = props;
        this.running = running;
        this.paused = paused;
    }

    @Override
    public void run() {
        log.info("Dispatch consumer {} started on {}", consumerName, streams.streamKey(channel));
        while (running.getAsBoolean()) {
            if (paused.getAsBoolean()) {
                sleep(200);
                continue;
            }
            try {
                Map<StreamMessageId, Map<String, String>> entries = stream.readGroup(DispatchStreams.GROUP, consumerName,
                        StreamReadGroupArgs.neverDelivered().count(1).timeout(props.readTimeout()));
                if (entries == null || entries.isEmpty()) {
                    continue;
                }
                entries.forEach(this::handle);
            } catch (Exception e) {
                if (running.getAsBoolean()) {
                    log.error("Consumer {} read failed on {}: {}", consumerName, streams.streamKey(channel), e.toString());
                    sleep(1000);
                }
            }
        }
        log.info("Dispatch consumer {} stopped", consumerName);
    }

    private void handle(StreamMessageId id, Map<String, String> fields) {
        DispatchMessage message;
        try {
            message = streams.decode(fields);
        } catch (RuntimeException e) {
            log.error("Discarding malformed entry {} on {}: {}", id, streams.streamKey(channel), e.getMessage());
            stream.ack(DispatchStreams.GROUP, id);
            return;
        }
        try {
            processor.process(message, false);
        } catch (RuntimeException e) {
            log.error("Chunk {} of campaign {} failed and stays pending for the reaper: {}", id, message.campaignId(), e.toString(), e);
            return;
        }
        stream.ack(DispatchStreams.GROUP, id);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
