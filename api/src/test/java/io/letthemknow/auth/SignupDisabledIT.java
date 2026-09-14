package io.letthemknow.auth;

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
 * A deployment that never sets {@code LTK_SIGNUP_CODE} must be closed, not open, and must be
 * indistinguishable from one that is configured and was given the wrong code. Otherwise the endpoint
 * becomes a probe for whether signup exists here, and for whether a guessed code was the only thing
 * missing. The paired assertion lives in {@link SignupIT#wrongCodeBodyMatchesTheDisabledDeploymentBody}.
 */
@TestPropertySource(properties = "ltk.security.signup-code=")
class SignupDisabledIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Test
    void unconfiguredSignupIsClosedAndSaysNothingMoreThanAWrongCodeWould() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("tenantName", "whoever", "email", "someone@example.com",
                "password", "S3cure-Pass!", "code", "any-code-at-all");

        ResponseEntity<String> res = rest.exchange("/api/v1/auth/signup", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains(SignupIT.REFUSAL_MESSAGE);
    }
}
