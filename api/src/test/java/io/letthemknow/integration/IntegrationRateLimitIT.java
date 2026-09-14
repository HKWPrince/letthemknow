package io.letthemknow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.auth.ApiKeyAuthFilter;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limiting with a deliberately tiny limit: the production default of 60/min would need 61 requests,
 * which can straddle the one-minute window and make the assertion flaky.
 */
@TestPropertySource(properties = "ltk.integration.rate-limit-per-minute=5")
class IntegrationRateLimitIT extends IntegrationTestBase {

    private static final int LIMIT = 5;

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    RestClientSupport api;

    /**
     * Apache HttpClient 5 honours {@code Retry-After} on 429 by default: it would sleep 60s and silently
     * retry, hiding the rejection. Real clients may do the same; this one must not.
     */
    private RestTemplate noRetry;

    @BeforeEach
    void setUp() {
        api = new RestClientSupport(rest).loginAsDemoAdmin();
        noRetry = new RestTemplate(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom().disableAutomaticRetries().build()));
        noRetry.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    private String newApiKey() {
        return data(api.post("/api/v1/api-keys", Map.of("name", "rl-" + UUID.randomUUID())), HttpStatus.CREATED)
                .get("key").asText();
    }

    private ResponseEntity<JsonNode> ping(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyAuthFilter.HEADER, key);
        return noRetry.exchange("http://localhost:" + port + "/api/v1/integration/ping", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    void requestsBeyondTheLimitAreRejectedWith429() {
        String key = newApiKey();

        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < LIMIT + 2; i++) {
            statuses.add(HttpStatus.valueOf(ping(key).getStatusCode().value()));
        }

        assertThat(statuses.subList(0, LIMIT)).containsOnly(HttpStatus.OK);
        assertThat(statuses.subList(LIMIT, statuses.size())).containsOnly(HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<JsonNode> rejected = ping(key);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody().get("code").asInt()).isEqualTo(429);
        assertThat(rejected.getBody().get("message").asText()).contains("Rate limit exceeded: 5 requests per minute");
        assertThat(rejected.getBody().get("data").isNull()).isTrue();
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
    }

    @Test
    void theLimitIsPerApiKey() {
        String exhausted = newApiKey();
        for (int i = 0; i < LIMIT; i++) {
            assertThat(ping(exhausted).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(ping(exhausted).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        String fresh = newApiKey();
        assertThat(ping(fresh).getStatusCode()).as("a different key has its own budget").isEqualTo(HttpStatus.OK);
        assertThat(ping(exhausted).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void unauthenticatedRequestsAreNotCountedAgainstAnyKey() {
        String key = newApiKey();
        for (int i = 0; i < LIMIT + 3; i++) {
            assertThat(ping("ltk_bogus123_00000000000000000000000000000000").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(ping(key).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
