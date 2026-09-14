package io.letthemknow.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code ltk.integration.*}: per-API-key rate limit for {@code /api/v1/integration/**}. */
@ConfigurationProperties(prefix = "ltk.integration")
public record IntegrationProperties(Integer rateLimitPerMinute) {

    public IntegrationProperties {
        if (rateLimitPerMinute == null || rateLimitPerMinute <= 0) {
            rateLimitPerMinute = 60;
        }
    }
}
