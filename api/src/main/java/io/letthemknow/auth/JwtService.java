package io.letthemknow.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.letthemknow.config.LtkSecurityProperties;
import io.letthemknow.tenant.User;
import io.letthemknow.tenant.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * HS256 JWTs signed with LTK_JWT_SECRET. Claims: {@code sub}=userId, {@code tid}, {@code role}, {@code email}.
 * No refresh tokens in v1.
 */
@Service
public class JwtService {

    static final String CLAIM_TENANT = "tid";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_EMAIL = "email";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public JwtService(LtkSecurityProperties props) {
        this(props, Clock.systemUTC());
    }

    JwtService(LtkSecurityProperties props, Clock clock) {
        byte[] secret = decodeSecret(props.jwtSecret());
        if (secret.length < 32) {
            throw new IllegalArgumentException("LTK_JWT_SECRET must be at least 32 bytes (256 bits) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.ttl = props.jwtTtl();
        this.clock = clock;
    }

    public record IssuedToken(String token, OffsetDateTime expiresAt) {}

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_TENANT, user.getTenantId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_EMAIL, user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, exp.atOffset(ZoneOffset.UTC));
    }

    /** Empty for any invalid, expired, or tampered token. */
    public Optional<LtkPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            Long tenantId = claims.get(CLAIM_TENANT, Long.class);
            UserRole role = UserRole.valueOf(claims.get(CLAIM_ROLE, String.class));
            String email = claims.get(CLAIM_EMAIL, String.class);
            if (tenantId == null) {
                return Optional.empty();
            }
            return Optional.of(LtkPrincipal.ofUser(tenantId, userId, email, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("LTK_JWT_SECRET is not set");
        }
        try {
            return Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
