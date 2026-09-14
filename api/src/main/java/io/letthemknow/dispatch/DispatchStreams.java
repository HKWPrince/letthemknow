package io.letthemknow.dispatch;

import io.letthemknow.channel.ChannelType;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.client.RedisException;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.EnumMap;
import java.util.Map;

/**
 * Redis handles: one stream per channel ({@code ltk:dispatch:email}, {@code ltk:dispatch:line}) with the
 * {@code dispatchers} consumer group, the retry delayed queue and the schedule delayed queue.
 * Group creation is idempotent (BUSYGROUP is ignored).
 */
@Component
public class DispatchStreams {

    public static final String GROUP = "dispatchers";
    public static final String FIELD_PAYLOAD = "payload";

    private static final Logger log = LoggerFactory.getLogger(DispatchStreams.class);

    private final RedissonClient redisson;
    private final DispatchMessageCodec codec;
    private final String prefix;
    private final Map<ChannelType, RStream<String, String>> streams = new EnumMap<>(ChannelType.class);
    private final RBlockingQueue<String> retryQueue;
    private final RDelayedQueue<String> retryDelayedQueue;
    private final RBlockingQueue<Long> scheduleQueue;
    private final RDelayedQueue<Long> scheduleDelayedQueue;
    private final String hostname;

    public DispatchStreams(RedissonClient redisson, DispatchMessageCodec codec, DispatchProperties props) {
        this.redisson = redisson;
        this.codec = codec;
        this.prefix = props.keyPrefix();
        for (ChannelType channel : ChannelType.values()) {
            streams.put(channel, redisson.getStream(streamKey(channel), StringCodec.INSTANCE));
        }
        this.retryQueue = redisson.getBlockingQueue(prefix + ":dispatch:retry", StringCodec.INSTANCE);
        this.retryDelayedQueue = redisson.getDelayedQueue(retryQueue);
        this.scheduleQueue = redisson.getBlockingQueue(prefix + ":campaign:schedule", LongCodec.INSTANCE);
        this.scheduleDelayedQueue = redisson.getDelayedQueue(scheduleQueue);
        this.hostname = resolveHostname();
    }

    /** {@code <prefix>:dispatch:email} / {@code <prefix>:dispatch:line}. */
    public String streamKey(ChannelType channel) {
        return prefix + ":dispatch:" + channel.name().toLowerCase();
    }

    @PostConstruct
    void bootstrapGroups() {
        streams.forEach((channel, stream) -> {
            try {
                stream.createGroup(StreamCreateGroupArgs.name(GROUP).makeStream());
                log.info("Created consumer group {} on {}", GROUP, streamKey(channel));
            } catch (RedisException e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.debug("Consumer group {} already exists on {}", GROUP, streamKey(channel));
                } else {
                    throw e;
                }
            }
        });
    }

    public RStream<String, String> stream(ChannelType channel) {
        return streams.get(channel);
    }

    public StreamMessageId add(DispatchMessage message) {
        return stream(message.channel()).add(StreamAddArgs.entry(FIELD_PAYLOAD, codec.encode(message)));
    }

    public DispatchMessage decode(Map<String, String> fields) {
        return codec.decode(fields.get(FIELD_PAYLOAD));
    }

    public RBlockingQueue<String> retryQueue() {
        return retryQueue;
    }

    public RDelayedQueue<String> retryDelayedQueue() {
        return retryDelayedQueue;
    }

    public RBlockingQueue<Long> scheduleQueue() {
        return scheduleQueue;
    }

    public RDelayedQueue<Long> scheduleDelayedQueue() {
        return scheduleDelayedQueue;
    }

    public DispatchMessageCodec codec() {
        return codec;
    }

    public String consumerName(int index) {
        return hostname + "-" + index;
    }

    public RedissonClient redisson() {
        return redisson;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
