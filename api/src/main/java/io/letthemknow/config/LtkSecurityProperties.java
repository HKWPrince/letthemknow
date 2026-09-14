package io.letthemknow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bound from {@code ltk.security.*}: LTK_MASTER_KEY, LTK_JWT_SECRET, JWT TTL. */
@ConfigurationProperties(prefix = "ltk.security")
public record LtkSecurityProperties(String masterKey, String jwtSecret, Duration jwtTtl) {

    public LtkSecurityProperties {
        if (jwtTtl == null) {
            jwtTtl = Duration.ofHours(12);
        }
    }
}
