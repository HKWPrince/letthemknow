package io.letthemknow.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Moves due retry entries from the delayed queue back onto their channel stream. */
class RetryQueueForwarder implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RetryQueueForwarder.class);

    private final DispatchStreams streams;
    private final BooleanSupplier running;

    RetryQueueForwarder(DispatchStreams streams, BooleanSupplier running) {
        this.streams = streams;
        this.running = running;
    }

    @Override
    public void run() {
        while (running.getAsBoolean()) {
            try {
                String payload = streams.retryQueue().poll(2, TimeUnit.SECONDS);
                if (payload == null) {
                    continue;
                }
                DispatchMessage message = streams.codec().decode(payload);
                streams.add(message);
                log.info("Retry attempt {} re-enqueued for campaign {} ({} recipient(s))",
                        message.attempt(), message.campaignId(), message.recipientIds().size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running.getAsBoolean()) {
                    log.error("Retry forwarder error: {}", e.toString());
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
}
