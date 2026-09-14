package io.letthemknow.integration;

import io.letthemknow.auth.AuthResponseWriter;
import io.letthemknow.auth.AuthType;
import io.letthemknow.auth.LtkPrincipal;
import io.letthemknow.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Redisson {@code RRateLimiter} per API key: 60 requests/minute by default, 429 on exceed.
 * Applies only to {@code /api/v1/integration/**} and only once an API key has authenticated.
 */
public class ApiKeyRateLimitFilter extends OncePerRequestFilter {

    static final String KEY_PREFIX = "ltk:ratelimit:apikey:";
    private static final Duration KEY_TTL = Duration.ofHours(1);

    private final RedissonClient redisson;
    private final AuthResponseWriter responseWriter;
    private final IntegrationProperties props;

    public ApiKeyRateLimitFilter(RedissonClient redisson, AuthResponseWriter responseWriter, IntegrationProperties props) {
        this.redisson = redisson;
        this.responseWriter = responseWriter;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/v1/integration/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LtkPrincipal principal)
                || principal.authType() != AuthType.API_KEY) {
            chain.doFilter(request, response);
            return;
        }
        int limit = props.rateLimitPerMinute();
        RRateLimiter limiter = redisson.getRateLimiter(KEY_PREFIX + principal.apiKeyId());
        if (limiter.trySetRate(RateType.OVERALL, limit, 1, RateIntervalUnit.MINUTES)) {
            limiter.expire(KEY_TTL);
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        if (!limiter.tryAcquire(1)) {
            response.setHeader("Retry-After", "60");
            responseWriter.write(response, ErrorCode.TOO_MANY_REQUESTS,
                    "Rate limit exceeded: " + limit + " requests per minute");
            return;
        }
        chain.doFilter(request, response);
    }
}
