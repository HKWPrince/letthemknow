package io.letthemknow.config;

import io.letthemknow.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browsers attach {@code Origin} to every POST, including same-origin ones, and behind a tunnel the
 * API never sees its own public hostname. So an unlisted origin makes Spring reject the request with
 * "Invalid CORS request" before any controller runs.
 *
 * <p>This was a live outage: the console could not log in or sign up at all, while curl worked, because
 * curl sends no Origin. These tests pin the behaviour so the configuration cannot silently regress.
 */
@TestPropertySource(properties = "ltk.security.cors-origins=https://console.example.com")
class CorsIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> loginFrom(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        return rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "nobody@example.com", "password", "whatever"), headers),
                String.class);
    }

    @Test
    void postFromTheConfiguredOriginReachesTheController() {
        ResponseEntity<String> res = loginFrom("https://console.example.com");

        // 401 means the request was processed and the credentials were wrong, which is the point:
        // it got past CORS. A 403 "Invalid CORS request" would mean it never reached the controller.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).doesNotContain("Invalid CORS request");
    }

    @Test
    void postFromAnUnlistedOriginIsRejected() {
        ResponseEntity<String> res = loginFrom("https://not-ours.example.com");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("Invalid CORS request");
    }

    @Test
    void postWithNoOriginHeaderIsUnaffected() {
        assertThat(loginFrom(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
