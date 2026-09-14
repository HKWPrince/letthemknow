package io.letthemknow.dispatch;

import io.letthemknow.channel.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts N consumer threads per stream plus the retry-queue forwarder; stops them gracefully:
 * consumers finish the in-flight chunk, un-ACKed entries are left for {@link PendingReaper}.
 * {@link #pause()} / {@link #resume()} stop reading new entries (used by tests and drain scenarios).
 */
@Component
@ConditionalOnProperty(prefix = "ltk.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispatchConsumerManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DispatchConsumerManager.class);

    private final DispatchStreams streams;
    private final ChunkProcessor processor;
    private final DispatchProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();

    public DispatchConsumerManager(DispatchStreams streams, ChunkProcessor processor, DispatchProperties props) {
        this.streams = streams;
        this.processor = processor;
        this.props = props;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int index = 0;
        for (ChannelType channel : ChannelType.values()) {
            for (int i = 0; i < props.consumersPerStream(); i++) {
                String name = streams.consumerName(index++);
                Thread t = new Thread(new DispatchConsumer(channel, name, streams, processor, props, running::get, paused::get),
                        "ltk-dispatch-" + channel.name().toLowerCase() + "-" + i);
                t.setDaemon(true);
                t.start();
                threads.add(t);
            }
        }
        Thread forwarder = new Thread(new RetryQueueForwarder(streams, running::get), "ltk-dispatch-retry");
        forwarder.setDaemon(true);
        forwarder.start();
        threads.add(forwarder);
        log.info("Dispatch workers started ({} consumer thread(s) per stream)", props.consumersPerStream());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping dispatch workers; in-flight chunks will finish");
        for (Thread t : threads) {
            try {
                t.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }
}
