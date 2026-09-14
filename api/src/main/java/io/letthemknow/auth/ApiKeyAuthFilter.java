package io.letthemknow.auth;

import io.letthemknow.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code X-API-KEY} on {@code /api/v1/integration/**} only. A present-but-invalid key is
 * rejected immediately with a 401 envelope; a missing key falls through to the entry point.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-KEY";
    public static final String INTEGRATION_PREFIX = "/api/v1/integration/";

    private final ApiKeyService apiKeyService;
    private final AuthResponseWriter responseWriter;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, AuthResponseWriter responseWriter) {
        this.apiKeyService = apiKeyService;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(INTEGRATION_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        var principal = apiKeyService.authenticate(rawKey.trim());
        if (principal.isEmpty()) {
            responseWriter.write(response, ErrorCode.UNAUTHORIZED, "Invalid API key");
            return;
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal.get(), null, principal.get().authorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}
