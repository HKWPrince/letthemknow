package io.letthemknow.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code ltk.dispatch.*} – chunk sizes, retry policy, reaper and poller cadence. */
@ConfigurationProperties(prefix = "ltk.dispatch")
public record DispatchProperties(
        String keyPrefix,
        Integer emailChunkSize,
        Integer lineChunkSize,
        Integer maxAttempts,
        Duration retryBase,
        Duration reaperIdle,
        Integer consumersPerStream,
        Duration readTimeout) {

    public DispatchProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "ltk";
        }
        if (emailChunkSize == null || emailChunkSize <= 0) {
            emailChunkSize = 50;
        }
        if (lineChunkSize == null || lineChunkSize <= 0 || lineChunkSize > 500) {
            lineChunkSize = 500;
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (retryBase == null) {
            retryBase = Duration.ofSeconds(30);
        }
        if (reaperIdle == null) {
            reaperIdle = Duration.ofMinutes(5);
        }
        if (consumersPerStream == null || consumersPerStream <= 0) {
            consumersPerStream = 1;
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(2);
        }
    }

    /** 30s · 2^attempt (attempt is zero-based). */
    public Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt, 0), 10);
        return retryBase.multipliedBy(factor);
    }
}
