package io.letthemknow.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.letthemknow.channel.ChannelConfigService;
import io.letthemknow.channel.dto.SmtpChannelRequest;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import io.letthemknow.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle endpoints with workers disabled (default test profile): campaigns reach PROCESSING and stay
 * there, so publish / abort / retry-failed / failures / stats can be driven deterministically.
 */
class CampaignLifecycleIT extends IntegrationTestBase {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("mailer", "secret"))
            .withPerMethodLifecycle(false);

    @Autowired TestRestTemplate rest;
    @Autowired TestTenants tenants;
    @Autowired ChannelConfigService channelConfigs;
    @Autowired JdbcTemplate jdbc;

    RestClientSupport api;
    long demoTenantId;

    @BeforeEach
    void setUp() {
        api = new RestClientSupport(rest).loginAsDemoAdmin();
        demoTenantId = tenants.demo().getId();
        TenantContextHolder.runAs(demoTenantId, () -> channelConfigs.upsertSmtp(new SmtpChannelRequest(
                "localhost", ServerSetupTest.SMTP.getPort(), "mailer", "secret",
                "noreply@demo.local", "Demo", false, "ops@demo.local")));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long createTemplate() {
        return data(api.post("/api/v1/templates", Map.of(
                "name", unique("lifecycle"), "channelType", "EMAIL", "subjectTemplate", "Hi {{name}}",
                "contentPayload", Map.of("html", "<p>Hello {{name}}</p>"))), HttpStatus.CREATED).get("id").asLong();
    }

    private long createCampaign() {
        return data(api.post("/api/v1/campaigns", Map.of(
                "title", unique("campaign"), "channelType", "EMAIL", "templateId", createTemplate(),
                "targetAudienceType", "CSV_LIST")), HttpStatus.CREATED).get("id").asLong();
    }

    private void addRecipients(long campaignId, int count) {
        List<Object[]> rows = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Object[]{demoTenantId, campaignId, "r" + i + "-" + campaignId + "@demo.local",
                        "{\"name\":\"R" + i + "\"}"})
                .toList();
        jdbc.batchUpdate("INSERT INTO campaign_recipients (tenant_id, campaign_id, recipient_identifier, payload_params, status, retry_count) "
                + "VALUES (?, ?, ?, ?, 'PENDING', 0)", rows);
    }

    private long readyCampaign(int recipients) {
        long id = createCampaign();
        addRecipients(id, recipients);
        return id;
    }

    @Test
    void publishStartsImmediatelyWhenNoScheduleGiven() {
        long id = readyCampaign(3);

        JsonNode published = data(api.post("/api/v1/campaigns/" + id + "/publish", null), HttpStatus.OK);

        assertThat(published.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(published.get("totalCount").asInt()).isEqualTo(3);
        assertThat(published.get("startedAt").isNull()).isFalse();

        JsonNode stats = data(api.get("/api/v1/campaigns/" + id + "/stats"), HttpStatus.OK);
        assertThat(stats.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(stats.get("processing").asBoolean()).isTrue();
        assertThat(stats.get("recipients").get("pending").asLong()).isEqualTo(3);
        assertThat(stats.get("failureBreakdown")).isEmpty();
    }

    @Test
    void publishWithFutureTimeSchedulesAndPastTimeStartsNow() {
        long scheduled = readyCampaign(1);
        JsonNode res = data(api.post("/api/v1/campaigns/" + scheduled + "/publish",
                Map.of("scheduledAt", "2099-01-01T00:00:00Z")), HttpStatus.OK);
        assertThat(res.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(res.get("scheduledAt").asText()).isEqualTo("2099-01-01T00:00:00Z");

        long past = readyCampaign(1);
        JsonNode now = data(api.post("/api/v1/campaigns/" + past + "/publish",
                Map.of("scheduledAt", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toString())), HttpStatus.OK);
        assertThat(now.get("status").asText()).isEqualTo("PROCESSING");

        // a SCHEDULED campaign can still be published again (re-scheduled)
        assertThat(api.post("/api/v1/campaigns/" + scheduled + "/publish", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void publishRejectsUnpublishableCampaigns() {
        long empty = createCampaign();
        ResponseEntity<JsonNode> noRecipients = api.post("/api/v1/campaigns/" + empty + "/publish", null);
        assertThat(noRecipients.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(noRecipients.getBody().get("message").asText()).contains("no recipients");

        long importing = readyCampaign(1);
        jdbc.update("UPDATE campaigns SET import_status = 'IMPORTING' WHERE id = ?", importing);
        assertThat(api.post("/api/v1/campaigns/" + importing + "/publish", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        long started = readyCampaign(1);
        data(api.post("/api/v1/campaigns/" + started + "/publish", null), HttpStatus.OK);
        ResponseEntity<JsonNode> twice = api.post("/api/v1/campaigns/" + started + "/publish", null);
        assertThat(twice.getStatusCode()).as("PROCESSING → publish is illegal").isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asInt()).isEqualTo(409);
    }

    @Test
    void abortTerminatesAndCancelsPendingRecipients() {
        long id = readyCampaign(4);
        data(api.post("/api/v1/campaigns/" + id + "/publish", null), HttpStatus.OK);

        JsonNode aborted = data(api.post("/api/v1/campaigns/" + id + "/abort", null), HttpStatus.OK);

        assertThat(aborted.get("status").asText()).isEqualTo("TERMINATED");
        assertThat(aborted.get("finishedAt").isNull()).isFalse();
        JsonNode stats = data(api.get("/api/v1/campaigns/" + id + "/stats"), HttpStatus.OK);
        assertThat(stats.get("recipients").get("cancelled").asLong()).isEqualTo(4);
        assertThat(stats.get("recipients").get("pending").asLong()).isZero();
        assertThat(stats.get("processing").asBoolean()).isFalse();

        ResponseEntity<JsonNode> again = api.post("/api/v1/campaigns/" + id + "/abort", null);
        assertThat(again.getStatusCode()).as("TERMINATED is final").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void failuresAndRetryFailedFromAwaitingResolution() {
        long id = readyCampaign(5);
        data(api.post("/api/v1/campaigns/" + id + "/publish", null), HttpStatus.OK);

        // simulate the dispatcher having finished: 2 sent, 3 failed with two different codes
        List<Long> ids = jdbc.queryForList("SELECT id FROM campaign_recipients WHERE campaign_id = ? ORDER BY id", Long.class, id);
        jdbc.update("UPDATE campaign_recipients SET status='SENT', sent_at=CURRENT_TIMESTAMP(6) WHERE id IN (?,?)", ids.get(0), ids.get(1));
        jdbc.update("UPDATE campaign_recipients SET status='FAILED', error_code='SMTP_INVALID_ADDRESS', error_message='550 user unknown' WHERE id IN (?,?)", ids.get(2), ids.get(3));
        jdbc.update("UPDATE campaign_recipients SET status='FAILED', error_code='NETWORK', error_message='timeout' WHERE id = ?", ids.get(4));
        jdbc.update("UPDATE campaigns SET status='AWAITING_RESOLUTION', success_count=2, failed_count=3 WHERE id = ?", id);

        JsonNode stats = data(api.get("/api/v1/campaigns/" + id + "/stats"), HttpStatus.OK);
        assertThat(stats.get("status").asText()).isEqualTo("AWAITING_RESOLUTION");
        assertThat(stats.get("failedCount").asInt()).isEqualTo(3);
        assertThat(stats.get("failureBreakdown")).hasSize(2);
        assertThat(stats.get("failureBreakdown").get(0).get("errorCode").asText()).isEqualTo("SMTP_INVALID_ADDRESS");
        assertThat(stats.get("failureBreakdown").get(0).get("count").asLong()).isEqualTo(2);

        JsonNode failures = data(api.get("/api/v1/campaigns/" + id + "/failures"), HttpStatus.OK);
        assertThat(failures.get("totalElements").asLong()).isEqualTo(3);
        assertThat(failures.get("items").get(0).get("errorMessage").asText()).isEqualTo("550 user unknown");

        JsonNode filtered = data(api.get("/api/v1/campaigns/" + id + "/failures?errorCode=NETWORK"), HttpStatus.OK);
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.get("items").get(0).get("errorCode").asText()).isEqualTo("NETWORK");

        JsonNode retried = data(api.post("/api/v1/campaigns/" + id + "/retry-failed", null), HttpStatus.OK);
        assertThat(retried.get("status").asText()).isEqualTo("RETRYING");

        JsonNode afterRetry = data(api.get("/api/v1/campaigns/" + id + "/stats"), HttpStatus.OK);
        assertThat(afterRetry.get("recipients").get("pending").asLong()).isEqualTo(3);
        assertThat(afterRetry.get("recipients").get("failed").asLong()).isZero();
        assertThat(afterRetry.get("failureBreakdown")).isEmpty();
        assertThat(data(api.get("/api/v1/campaigns/" + id + "/recipients?status=PENDING"), HttpStatus.OK)
                .get("items").get(0).get("retryCount").asInt()).isEqualTo(1);
    }

    @Test
    void retryFailedRejectedUnlessAwaitingResolution() {
        long id = readyCampaign(1);

        ResponseEntity<JsonNode> draft = api.post("/api/v1/campaigns/" + id + "/retry-failed", null);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(draft.getBody().get("message").asText()).contains("DRAFT");

        data(api.post("/api/v1/campaigns/" + id + "/publish", null), HttpStatus.OK);
        assertThat(api.post("/api/v1/campaigns/" + id + "/retry-failed", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lifecycleEndpointsHideOtherTenantsCampaigns() {
        long otherTenant = tenants.provision("other-lifecycle").tenant().getId();
        Long foreignCampaign = TenantContextHolder.runAs(otherTenant, () -> {
            jdbc.update("INSERT INTO message_templates (tenant_id, name, channel_type, template_type, subject_template, content_payload) "
                    + "VALUES (?, 'foreign', 'EMAIL', 'EMAIL_HTML', 's', '{\"html\":\"<p>x</p>\"}')", otherTenant);
            Long templateId = jdbc.queryForObject("SELECT id FROM message_templates WHERE tenant_id = ? AND name = 'foreign'", Long.class, otherTenant);
            jdbc.update("INSERT INTO campaigns (tenant_id, title, channel_type, template_id, status, target_audience_type) "
                    + "VALUES (?, 'foreign', 'EMAIL', ?, 'AWAITING_RESOLUTION', 'CSV_LIST')", otherTenant, templateId);
            return jdbc.queryForObject("SELECT id FROM campaigns WHERE tenant_id = ? AND title = 'foreign'", Long.class, otherTenant);
        });

        // every endpoint must answer 404 – never 403, and never leak that the row exists
        for (String path : List.of("/api/v1/campaigns/" + foreignCampaign,
                "/api/v1/campaigns/" + foreignCampaign + "/stats",
                "/api/v1/campaigns/" + foreignCampaign + "/failures",
                "/api/v1/campaigns/" + foreignCampaign + "/recipients")) {
            assertThat(api.get(path).getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
        for (String path : List.of("/api/v1/campaigns/" + foreignCampaign + "/publish",
                "/api/v1/campaigns/" + foreignCampaign + "/abort",
                "/api/v1/campaigns/" + foreignCampaign + "/retry-failed")) {
            ResponseEntity<JsonNode> res = api.post(path, null);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(res.getBody().get("code").asInt()).isEqualTo(404);
        }
        assertThat(api.delete("/api/v1/campaigns/" + foreignCampaign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/campaigns/999999999/stats").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lifecycleEndpointsRequireAuthentication() {
        long id = readyCampaign(1);
        RestClientSupport anonymous = new RestClientSupport(rest);

        assertThat(anonymous.post("/api/v1/campaigns/" + id + "/publish", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.get("/api/v1/campaigns/" + id + "/stats").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
