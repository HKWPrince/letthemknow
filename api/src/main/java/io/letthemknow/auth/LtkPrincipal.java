package io.letthemknow.auth;

import io.letthemknow.tenant.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authenticated identity placed in the Spring Security context.
 * JWT principals carry the user; API-key principals carry the key and get {@code ROLE_INTEGRATION}.
 */
public record LtkPrincipal(Long tenantId, Long userId, String email, UserRole role, AuthType authType, Long apiKeyId) {

    public static final String ROLE_INTEGRATION = "INTEGRATION";

    public static LtkPrincipal ofUser(Long tenantId, Long userId, String email, UserRole role) {
        return new LtkPrincipal(tenantId, userId, email, role, AuthType.JWT, null);
    }

    public static LtkPrincipal ofApiKey(Long tenantId, Long apiKeyId) {
        return new LtkPrincipal(tenantId, null, null, null, AuthType.API_KEY, apiKeyId);
    }

    public List<GrantedAuthority> authorities() {
        String roleName = authType == AuthType.API_KEY ? ROLE_INTEGRATION : role.name();
        return List.of(new SimpleGrantedAuthority("ROLE_" + roleName));
    }

    public String name() {
        return authType == AuthType.API_KEY ? "apikey:" + apiKeyId : "user:" + userId;
    }
}
