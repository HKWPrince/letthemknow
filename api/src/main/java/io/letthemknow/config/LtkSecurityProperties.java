package io.letthemknow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from {@code ltk.security.*}: LTK_MASTER_KEY, LTK_JWT_SECRET, JWT TTL, LTK_SIGNUP_CODE.
 *
 * <p>{@code signupCode} gates self-service tenant creation. Blank or unset means signup is
 * <em>disabled</em>, never open: a deployment that never sets it cannot be signed up to.
 */
@ConfigurationProperties(prefix = "ltk.security")
public record LtkSecurityProperties(String masterKey, String jwtSecret, Duration jwtTtl, String signupCode) {

    public LtkSecurityProperties {
        if (jwtTtl == null) {
            jwtTtl = Duration.ofHours(12);
        }
    }

    /** True only when an operator has configured a code. */
    public boolean signupEnabled() {
        return signupCode != null && !signupCode.isBlank();
    }
}
