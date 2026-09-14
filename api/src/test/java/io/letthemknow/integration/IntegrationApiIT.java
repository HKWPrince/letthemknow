package io.letthemknow.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.letthemknow.auth.ApiKeyAuthFilter;
import io.letthemknow.channel.ChannelConfigService;
import io.letthemknow.channel.dto.LineChannelRequest;
import io.letthemknow.channel.dto.SmtpChannelRequest;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import io.letthemknow.support.TestTenants;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code /api/v1/integration/**}: X-API-KEY only, single push, message lookup, per-key rate limit. */
class IntegrationApiIT extends IntegrationTestBase {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("mailer", "secret"))
            .withPerMethodLifecycle(false);

    /** Port must match ltk.line.base-url in application-test.yml. */
    @RegisterExtension
    static WireMockExtension line = WireMockExtension.newInstance().options(wireMockConfig().port(18089)).build();

    @Autowired TestRestTemplate rest;
    @Autowired TestTenants tenants;
    @Autowired ChannelConfigService channelConfigs;
    @Autowired JdbcTemplate jdbc;

    RestClientSupport api;
    long tenantId;
    String apiKey;
    long emailTemplateId;
    String emailTemplateName;
    long lineTemplateId;

    @BeforeEach
    void setUp() throws Exception {
        line.resetAll();
        greenMail.purgeEmailFromAllMailboxes();
        api = new RestClientSupport(rest).loginAsDemoAdmin();
        tenantId = tenants.demo().getId();

        TenantContextHolder.runAs(tenantId, () -> {
            channelConfigs.upsertSmtp(new SmtpChannelRequest("localhost", ServerSetupTest.SMTP.getPort(),
                    "mailer", "secret", "noreply@demo.local", "Demo", false, "ops@demo.local"));
            channelConfigs.upsertLine(new LineChannelRequest("1234567890", "line-secret", "tok-123"));
        });
        greenMail.purgeEmailFromAllMailboxes();

        emailTemplateName = "push-" + UUID.randomUUID().toString().substring(0, 8);
        emailTemplateId = data(api.post("/api/v1/templates", Map.of(
                "name", emailTemplateName, "channelType", "EMAIL", "subjectTemplate", "Order {{order}}",
                "contentPayload", Map.of("html", "<p>Hi {{name}}, order {{order}} shipped</p>",
                        "text", "Hi {{name}}, order {{order}} shipped"))), HttpStatus.CREATED).get("id").asLong();
        lineTemplateId = data(api.post("/api/v1/templates", Map.of(
                "name", "line-" + UUID.randomUUID().toString().substring(0, 8), "channelType", "LINE",
                "contentPayload", Map.of("messages", List.of(Map.of("type", "text", "text", "Order {{order}} shipped"))))),
                HttpStatus.CREATED).get("id").asLong();

        apiKey = data(api.post("/api/v1/api-keys", Map.of("name", "erp-" + UUID.randomUUID())), HttpStatus.CREATED)
                .get("key").asText();
    }

    private ResponseEntity<JsonNode> callWithKey(String key, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyAuthFilter.HEADER, key);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> push(Object body) {
        return callWithKey(apiKey, HttpMethod.POST, "/api/v1/integration/push/single", body);
    }

    @Test
    void singlePushSendsEmailAndStoresTransactionalMessage() throws Exception {
        Map<String, Object> body = Map.of(
                "channel", "EMAIL", "templateId", emailTemplateId, "recipient", "ann@example.com",
                "params", Map.of("name", "Ann", "order", "A-42"));

        JsonNode sent = data(push(body), HttpStatus.OK);

        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("messageId").asLong()).isPositive();
        assertThat(sent.get("externalMessageId").asText()).isNotBlank();
        assertThat(sent.get("errorCode").isNull()).isTrue();
        assertThat(sent.get("sentAt").asText()).endsWith("Z");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Order A-42");
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("ann@example.com");

        JsonNode stored = data(callWithKey(apiKey, HttpMethod.GET,
                "/api/v1/integration/messages/" + sent.get("messageId").asLong(), null), HttpStatus.OK);
        assertThat(stored.get("status").asText()).isEqualTo("SENT");
        assertThat(stored.get("channelType").asText()).isEqualTo("EMAIL");
        assertThat(stored.get("recipientIdentifier").asText()).isEqualTo("ann@example.com");
        assertThat(stored.get("payloadParams").get("order").asText()).isEqualTo("A-42");
        assertThat(stored.get("templateId").asLong()).isEqualTo(emailTemplateId);
    }

    @Test
    void singlePushAcceptsTemplateNameAndSendsLineMessage() {
        line.stubFor(post(urlEqualTo("/v2/bot/message/push"))
                .withRequestBody(equalToJson("{\"to\":\"U00000000000000000000000000000001\","
                        + "\"messages\":[{\"type\":\"text\",\"text\":\"Order B-7 shipped\"}]}"))
                .willReturn(aResponse().withStatus(200).withHeader("X-Line-Request-Id", "req-1")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sentMessages\":[{\"id\":\"msg-99\"}]}")));

        JsonNode sent = data(push(Map.of("channel", "LINE", "templateId", lineTemplateId,
                "recipient", "U00000000000000000000000000000001", "params", Map.of("order", "B-7"))), HttpStatus.OK);
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("externalMessageId").asText()).isEqualTo("msg-99");

        JsonNode byName = data(push(Map.of("channel", "EMAIL", "templateName", emailTemplateName,
                "recipient", "bob@example.com", "params", Map.of("name", "Bob", "order", "C-1"))), HttpStatus.OK);
        assertThat(byName.get("status").asText()).isEqualTo("SENT");
    }

    @Test
    void deliveryFailureIsRecordedAsFailedMessage() {
        line.stubFor(post(urlEqualTo("/v2/bot/message/push"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"The property, 'to', in the request body is invalid\"}")));

        JsonNode failed = data(push(Map.of("channel", "LINE", "templateId", lineTemplateId,
                "recipient", "U00000000000000000000000000000002", "params", Map.of("order", "D-9"))), HttpStatus.OK);

        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asText()).isEqualTo("LINE_BAD_REQUEST");
        assertThat(failed.get("errorMessage").asText()).contains("400");
        assertThat(failed.get("sentAt").isNull()).isTrue();

        JsonNode stored = data(callWithKey(apiKey, HttpMethod.GET,
                "/api/v1/integration/messages/" + failed.get("messageId").asLong(), null), HttpStatus.OK);
        assertThat(stored.get("status").asText()).isEqualTo("FAILED");
        assertThat(stored.get("errorCode").asText()).isEqualTo("LINE_BAD_REQUEST");
    }

    @Test
    void singlePushValidatesTemplateSelectorAndChannel() {
        Map<String, Object> neither = new HashMap<>(Map.of("channel", "EMAIL", "recipient", "x@example.com"));
        ResponseEntity<JsonNode> none = push(neither);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody().get("message").asText()).contains("exactly one");

        assertThat(push(Map.of("channel", "EMAIL", "templateId", emailTemplateId,
                "templateName", emailTemplateName, "recipient", "x@example.com")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> mismatch = push(Map.of("channel", "LINE", "templateId", emailTemplateId,
                "recipient", "U00000000000000000000000000000003"));
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody().get("message").asText()).contains("does not match");

        assertThat(push(Map.of("channel", "EMAIL", "templateId", 999_999_999L, "recipient", "x@example.com"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(push(Map.of("channel", "EMAIL", "templateName", "no-such-template", "recipient", "x@example.com"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> noRecipient = new HashMap<>();
        noRecipient.put("channel", "EMAIL");
        noRecipient.put("templateId", emailTemplateId);
        noRecipient.put("recipient", "");
        assertThat(push(noRecipient).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void messagesAreScopedToTheKeysTenant() {
        long messageId = data(push(Map.of("channel", "EMAIL", "templateId", emailTemplateId,
                "recipient", "scoped@example.com", "params", Map.of("name", "S", "order", "E-1"))), HttpStatus.OK)
                .get("messageId").asLong();

        long otherTenant = tenants.provision("other-integration").tenant().getId();
        // prefix must be exactly 8 chars and the secret exactly 32, per the ltk_<8>_<32> format
        String foreignKey = "ltk_foreign1_00000000000000000000000000000000";
        TenantContextHolder.runAs(otherTenant, () -> jdbc.update(
                "INSERT INTO api_keys (tenant_id, name, api_key_prefix, api_key_hash, status) "
                        + "VALUES (?, 'foreign', 'foreign1', ?, 'ACTIVE')", otherTenant, sha256(foreignKey)));

        assertThat(callWithKey(foreignKey, HttpMethod.GET, "/api/v1/integration/messages/" + messageId, null)
                .getStatusCode()).as("cross-tenant lookup must 404").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(callWithKey(apiKey, HttpMethod.GET, "/api/v1/integration/messages/" + messageId, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(callWithKey(apiKey, HttpMethod.GET, "/api/v1/integration/messages/999999999", null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rateLimitHeaderAdvertisesTheConfiguredLimit() {
        ResponseEntity<JsonNode> res = callWithKey(apiKey, HttpMethod.GET, "/api/v1/integration/ping", null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("60");
    }

    @Test
    void openApiDocumentDescribesBothSecuritySchemes() {
        ResponseEntity<JsonNode> spec = rest.getForEntity("/api/docs/openapi", JsonNode.class);

        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode schemes = spec.getBody().get("components").get("securitySchemes");
        assertThat(schemes.get("bearerAuth").get("scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.get("apiKey").get("name").asText()).isEqualTo("X-API-KEY");
        JsonNode paths = spec.getBody().get("paths");
        assertThat(paths.has("/api/v1/integration/push/single")).isTrue();
        assertThat(paths.has("/api/v1/campaigns/{id}/publish")).isTrue();
        assertThat(paths.has("/api/v1/auth/login")).isTrue();
    }

    @Test
    void integrationGroupExposesOnlyTheThreeTenantEndpoints() {
        ResponseEntity<JsonNode> group = rest.getForEntity("/api/docs/openapi/integration", JsonNode.class);

        assertThat(group.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode paths = group.getBody().get("paths");
        assertThat(paths.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/api/v1/integration/push/single",
                "/api/v1/integration/messages/{id}",
                "/api/v1/integration/ping");
        // a tenant's developer must not be handed the console's admin surface
        assertThat(paths.toString()).doesNotContain("/api/v1/campaigns").doesNotContain("/api/v1/auth");
    }

    @Test
    void swaggerUiIsReachableWithoutAuthentication() {
        // regression: the UI shell lives at /api/docs but its assets at /api/swagger-ui/**. When only the
        // former was public, "OpenAPI reference" answered 401 with a JSON error instead of rendering.
        assertThat(rest.getForEntity("/api/swagger-ui/index.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/docs/openapi/console", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private static String sha256(String value) throws RuntimeException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
