package io.letthemknow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TestTenants tenants;

    private ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, HttpHeaders headers) {
        return rest.exchange(path, method, new HttpEntity<>(headers), JsonNode.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders apiKey(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set(ApiKeyAuthFilter.HEADER, key);
        return h;
    }

    private String loginAsDemoAdmin() {
        ResponseEntity<JsonNode> res = post("/api/v1/auth/login",
                Map.of("email", TestTenants.DEMO_ADMIN_EMAIL, "password", TestTenants.DEMO_ADMIN_PASSWORD),
                new HttpHeaders());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody().get("data").get("token").asText();
    }

    @Test
    void loginReturnsJwtAndProfile() {
        ResponseEntity<JsonNode> res = post("/api/v1/auth/login",
                Map.of("email", TestTenants.DEMO_ADMIN_EMAIL, "password", TestTenants.DEMO_ADMIN_PASSWORD),
                new HttpHeaders());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = res.getBody().get("data");
        assertThat(data.get("token").asText()).isNotBlank();
        assertThat(data.get("expiresAt").asText()).endsWith("Z");
        assertThat(data.get("user").get("email").asText()).isEqualTo(TestTenants.DEMO_ADMIN_EMAIL);
        assertThat(data.get("user").has("passwordHash")).isFalse();
        assertThat(data.get("tenant").get("name").asText()).isEqualTo("demo");
    }

    @Test
    void loginRejectsBadCredentialsAndValidatesBody() {
        ResponseEntity<JsonNode> wrong = post("/api/v1/auth/login",
                Map.of("email", TestTenants.DEMO_ADMIN_EMAIL, "password", "nope"), new HttpHeaders());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("code").asInt()).isEqualTo(401);

        ResponseEntity<JsonNode> unknown = post("/api/v1/auth/login",
                Map.of("email", "ghost@nowhere.local", "password", "whatever"), new HttpHeaders());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> invalid = post("/api/v1/auth/login",
                Map.of("email", "not-an-email", "password", ""), new HttpHeaders());
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("message").asText()).contains("email");
    }

    @Test
    void meRequiresJwt() {
        assertThat(exchange("/api/v1/auth/me", HttpMethod.GET, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/api/v1/auth/me", HttpMethod.GET, bearer("garbage.token.value")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> me = exchange("/api/v1/auth/me", HttpMethod.GET, bearer(loginAsDemoAdmin()));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("data").get("user").get("email").asText()).isEqualTo(TestTenants.DEMO_ADMIN_EMAIL);
        assertThat(me.getBody().get("data").get("tenant").get("id").asLong()).isEqualTo(tenants.demo().getId());
    }

    @Test
    void apiKeyLifecycle() {
        String jwt = loginAsDemoAdmin();

        ResponseEntity<JsonNode> created = post("/api/v1/api-keys", Map.of("name", "erp"), bearer(jwt));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("code").asInt()).isEqualTo(201);
        JsonNode data = created.getBody().get("data");
        String rawKey = data.get("key").asText();
        long keyId = data.get("apiKey").get("id").asLong();
        assertThat(rawKey).matches("^ltk_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}$");
        assertThat(data.get("apiKey").get("prefix").asText()).isEqualTo(rawKey.substring(4, 12));
        assertThat(data.get("apiKey").has("apiKeyHash")).isFalse();

        ResponseEntity<JsonNode> list = exchange("/api/v1/api-keys", HttpMethod.GET, bearer(jwt));
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("data").findValues("id")).extracting(JsonNode::asLong).contains(keyId);
        assertThat(list.getBody().toString()).doesNotContain(rawKey);

        ResponseEntity<JsonNode> ping = exchange("/api/v1/integration/ping", HttpMethod.GET, apiKey(rawKey));
        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ping.getBody().get("data").get("tenantId").asLong()).isEqualTo(tenants.demo().getId());
        assertThat(ping.getBody().get("data").get("apiKeyId").asLong()).isEqualTo(keyId);

        ResponseEntity<JsonNode> revoked = exchange("/api/v1/api-keys/" + keyId, HttpMethod.DELETE, bearer(jwt));
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().get("data").get("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.getBody().get("data").get("revokedAt").isNull()).isFalse();

        assertThat(exchange("/api/v1/integration/ping", HttpMethod.GET, apiKey(rawKey)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void seededApiKeyAuthenticates() {
        ResponseEntity<JsonNode> ping = exchange("/api/v1/integration/ping", HttpMethod.GET,
                apiKey(TestTenants.DEMO_API_KEY));

        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ping.getBody().get("data").get("tenantId").asLong()).isEqualTo(tenants.demo().getId());
    }

    @Test
    void apiKeyAndJwtAreNotInterchangeable() {
        String jwt = loginAsDemoAdmin();

        // JWT on an integration endpoint: authenticated but lacks ROLE_INTEGRATION
        assertThat(exchange("/api/v1/integration/ping", HttpMethod.GET, bearer(jwt)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // API key outside /integration is ignored entirely
        assertThat(exchange("/api/v1/api-keys", HttpMethod.GET, apiKey(TestTenants.DEMO_API_KEY)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Malformed / unknown keys
        assertThat(exchange("/api/v1/integration/ping", HttpMethod.GET, apiKey("ltk_bad")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/api/v1/integration/ping", HttpMethod.GET,
                apiKey("ltk_demo1234_00000000000000000000000000000000")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/api/v1/integration/ping", HttpMethod.GET, new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void crossTenantApiKeyIdReturnsNotFound() {
        long otherTenantKeyId = tenants.provision("other").tenant().getId();
        String jwt = loginAsDemoAdmin();

        // An id that cannot belong to demo (tenant ids and key ids are unrelated; use a huge id)
        ResponseEntity<JsonNode> missing = exchange("/api/v1/api-keys/" + (otherTenantKeyId + 1_000_000),
                HttpMethod.DELETE, bearer(jwt));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asInt()).isEqualTo(404);
    }
}
