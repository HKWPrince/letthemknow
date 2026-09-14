package io.letthemknow.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Small REST helpers shared by controller integration tests. */
public final class RestClientSupport {

    private final TestRestTemplate rest;
    private String token;

    public RestClientSupport(TestRestTemplate rest) {
        this.rest = rest;
    }

    public RestClientSupport loginAsDemoAdmin() {
        ResponseEntity<JsonNode> res = post("/api/v1/auth/login",
                Map.of("email", TestTenants.DEMO_ADMIN_EMAIL, "password", TestTenants.DEMO_ADMIN_PASSWORD));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        token = res.getBody().get("data").get("token").asText();
        return this;
    }

    public HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    public ResponseEntity<JsonNode> post(String path, Object body) {
        return exchange(path, HttpMethod.POST, body);
    }

    public ResponseEntity<JsonNode> put(String path, Object body) {
        return exchange(path, HttpMethod.PUT, body);
    }

    public ResponseEntity<JsonNode> get(String path) {
        return exchange(path, HttpMethod.GET, null);
    }

    public ResponseEntity<JsonNode> delete(String path) {
        return exchange(path, HttpMethod.DELETE, null);
    }

    public ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body) {
        HttpHeaders h = headers();
        if (body != null) {
            h.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    public ResponseEntity<JsonNode> multipart(String path, HttpEntity<?> entity) {
        return rest.exchange(path, HttpMethod.POST, entity, JsonNode.class);
    }

    public static JsonNode data(ResponseEntity<JsonNode> res, HttpStatus expected) {
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(expected);
        return res.getBody().get("data");
    }
}
