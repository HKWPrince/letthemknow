package io.letthemknow.config;

import io.letthemknow.auth.ApiKeyAuthFilter;
import io.letthemknow.auth.ApiKeyService;
import io.letthemknow.auth.AuthResponseWriter;
import io.letthemknow.auth.JwtAuthFilter;
import io.letthemknow.auth.JwtService;
import io.letthemknow.auth.LtkPrincipal;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextFilter;
import io.letthemknow.integration.ApiKeyRateLimitFilter;
import io.letthemknow.integration.IntegrationProperties;
import io.letthemknow.tenant.UserRole;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless security: JWT (Bearer) for the console, X-API-KEY only on {@code /api/v1/integration/**}.
 * Filter order: JwtAuthFilter → ApiKeyAuthFilter → TenantContextFilter. 401/403 are JSON envelopes.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    private final AuthResponseWriter responseWriter;

    SecurityConfig(AuthResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, ApiKeyService apiKeyService,
                                            RedissonClient redisson, IntegrationProperties integrationProps)
            throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService);
        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(apiKeyService, responseWriter);
        ApiKeyRateLimitFilter rateLimitFilter = new ApiKeyRateLimitFilter(redisson, responseWriter, integrationProps);
        TenantContextFilter tenantContextFilter = new TenantContextFilter();

        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                responseWriter.write(res, ErrorCode.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((req, res, e) ->
                                responseWriter.write(res, ErrorCode.FORBIDDEN, "Access denied")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/health", "/api/v1/health/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // springdoc serves the UI shell from /api/docs but its assets from /api/swagger-ui/**,
                        // so both prefixes must be public or the reference page answers 401.
                        .requestMatchers("/api/docs", "/api/docs/**", "/api/swagger-ui/**",
                                "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/integration/**").hasRole(LtkPrincipal.ROLE_INTEGRATION)
                        .anyRequest().hasRole(UserRole.ADMIN.name()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyAuthFilter, JwtAuthFilter.class)
                .addFilterAfter(rateLimitFilter, ApiKeyAuthFilter.class)
                .addFilterAfter(tenantContextFilter, ApiKeyRateLimitFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
