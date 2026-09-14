package io.letthemknow.auth;

import io.letthemknow.config.LtkSecurityProperties;
import io.letthemknow.tenant.User;
import io.letthemknow.tenant.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Instant T0 = Instant.parse("2026-09-10T00:00:00Z");

    private static JwtService serviceAt(Instant now, String secret) {
        return new JwtService(new LtkSecurityProperties("unused", secret, Duration.ofHours(12)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static User user() {
        User user = new User(7L, "admin@demo.local", "hash", UserRole.ADMIN);
        ReflectionTestUtils.setField(user, "id", 42L);
        return user;
    }

    @Test
    void roundTripsClaims() {
        JwtService service = serviceAt(T0, SECRET);
        JwtService.IssuedToken issued = service.issue(user());

        assertThat(issued.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC));
        Optional<LtkPrincipal> principal = service.parse(issued.token());
        assertThat(principal).isPresent();
        assertThat(principal.get().tenantId()).isEqualTo(7L);
        assertThat(principal.get().userId()).isEqualTo(42L);
        assertThat(principal.get().email()).isEqualTo("admin@demo.local");
        assertThat(principal.get().role()).isEqualTo(UserRole.ADMIN);
        assertThat(principal.get().authType()).isEqualTo(AuthType.JWT);
        assertThat(principal.get().authorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void rejectsExpiredToken() {
        String token = serviceAt(T0, SECRET).issue(user()).token();
        JwtService later = serviceAt(T0.plus(Duration.ofHours(13)), SECRET);

        assertThat(later.parse(token)).isEmpty();
    }

    @Test
    void rejectsTamperedAndForeignTokens() {
        String token = serviceAt(T0, SECRET).issue(user()).token();
        String tampered = token.substring(0, token.length() - 4) + "abcd";
        JwtService otherSecret = serviceAt(T0, Base64.getEncoder().encodeToString("another-32-byte-secret-for-tests!!".getBytes()));

        assertThat(serviceAt(T0, SECRET).parse(tampered)).isEmpty();
        assertThat(otherSecret.parse(token)).isEmpty();
        assertThat(serviceAt(T0, SECRET).parse("not.a.jwt")).isEmpty();
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> serviceAt(T0, "short")).isInstanceOf(IllegalArgumentException.class);
    }
}
