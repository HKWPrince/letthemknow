package io.letthemknow.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthReturnsApiResponseEnvelope() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code").asInt()).isEqualTo(200);
        assertThat(body.get("message").asText()).isEqualTo("OK");
        assertThat(body.get("data").get("status").asText()).isEqualTo("UP");
        assertThat(body.get("data").get("time").asText()).endsWith("Z");
    }

    @Test
    void unknownRouteIsNotFoundWithEnvelope() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/health/does-not-exist", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asInt()).isEqualTo(404);
        assertThat(response.getBody().get("data").isNull()).isTrue();
    }

    @Test
    void protectedRouteIsUnauthorizedWithEnvelope() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/campaigns", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asInt()).isEqualTo(401);
        assertThat(response.getBody().get("data").isNull()).isTrue();
    }
}
