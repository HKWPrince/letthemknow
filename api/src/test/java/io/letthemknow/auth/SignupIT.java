package io.letthemknow.auth;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Signup with a configured code. {@link SignupDisabledIT} covers the unset case. */
@TestPropertySource(properties = "ltk.security.signup-code=" + SignupIT.CODE)
class SignupIT extends IntegrationTestBase {

    static final String CODE = "test-signup-code";

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<JsonNode> signup(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/auth/signup", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static Map<String, String> request(String tenantName, String code) {
        return Map.of("tenantName", tenantName,
                "email", "admin@" + tenantName + ".example",
                "password", "S3cure-Pass!",
                "code", code);
    }

    private static String uniqueName() {
        return "acme" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void correctCodeCreatesTenantAndReturnsUsableSession() {
        String name = uniqueName();
        ResponseEntity<JsonNode> res = signup(request(name, CODE));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = res.getBody().get("data");
        assertThat(data.get("tenant").get("name").asText()).isEqualTo(name);
        assertThat(data.get("user").get("role").asText()).isEqualTo("ADMIN");

        // The token must actually work, otherwise "signed in" is a claim rather than a fact.
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(data.get("token").asText());
        ResponseEntity<JsonNode> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("data").get("tenant").get("name").asText()).isEqualTo(name);
    }

    @Test
    void wrongCodeIsRefused() {
        ResponseEntity<JsonNode> res = signup(request(uniqueName(), "not-the-code"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void duplicateTenantNameConflicts() {
        String name = uniqueName();
        assertThat(signup(request(name, CODE)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> second = signup(request(name, CODE));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shortPasswordIsRejectedBeforeAnythingIsCreated() {
        ResponseEntity<JsonNode> res = signup(Map.of("tenantName", uniqueName(),
                "email", "admin@short.example", "password", "short", "code", CODE));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A wrong code must not reveal that signup is configured here. Compared against {@code SignupDisabledIT}. */
    @Test
    void wrongCodeBodyMatchesTheDisabledDeploymentBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.exchange("/api/v1/auth/signup", HttpMethod.POST,
                new HttpEntity<>(request(uniqueName(), "not-the-code"), headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains(REFUSAL_MESSAGE);
    }

    /** Shared with {@code SignupDisabledIT} so the two assertions cannot drift apart. */
    static final String REFUSAL_MESSAGE = "Signup is not available with that code";
}
