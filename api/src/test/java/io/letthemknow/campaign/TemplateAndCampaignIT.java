package io.letthemknow.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;

class TemplateAndCampaignIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    RestClientSupport api;

    @BeforeEach
    void setUp() {
        api = new RestClientSupport(rest).loginAsDemoAdmin();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> emailTemplate(String name) {
        return Map.of(
                "name", name,
                "channelType", "EMAIL",
                "subjectTemplate", "Hello {{name}}",
                "contentPayload", Map.of("html", "<p>Hi {{name}}, your code is {{code}}</p>", "text", "Hi {{name}}"));
    }

    private long createEmailTemplate() {
        return data(api.post("/api/v1/templates", emailTemplate(unique("welcome"))), HttpStatus.CREATED).get("id").asLong();
    }

    @Test
    void templateCrudAndValidation() {
        String name = unique("tpl");
        JsonNode created = data(api.post("/api/v1/templates", emailTemplate(name)), HttpStatus.CREATED);
        long id = created.get("id").asLong();
        assertThat(created.get("templateType").asText()).isEqualTo("EMAIL_HTML");
        assertThat(created.get("contentPayload").get("html").asText()).contains("{{name}}");

        assertThat(api.post("/api/v1/templates", emailTemplate(name)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> noHtml = new HashMap<>(emailTemplate(unique("bad")));
        noHtml.put("contentPayload", Map.of("text", "only text"));
        ResponseEntity<JsonNode> bad = api.post("/api/v1/templates", noHtml);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("message").asText()).contains("html");

        Map<String, Object> noSubject = new HashMap<>(emailTemplate(unique("bad")));
        noSubject.remove("subjectTemplate");
        assertThat(api.post("/api/v1/templates", noSubject).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        List<Map<String, String>> six = java.util.Collections.nCopies(6, Map.of("type", "text", "text", "x"));
        ResponseEntity<JsonNode> tooMany = api.post("/api/v1/templates", Map.of(
                "name", unique("line"), "channelType", "LINE", "contentPayload", Map.of("messages", six)));
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode lineTpl = data(api.post("/api/v1/templates", Map.of(
                "name", unique("line"), "channelType", "LINE",
                "contentPayload", Map.of("messages", List.of(Map.of("type", "text", "text", "Hi {{name}}"))))), HttpStatus.CREATED);
        assertThat(lineTpl.get("templateType").asText()).isEqualTo("LINE_MESSAGES");

        JsonNode updated = data(api.put("/api/v1/templates/" + id, Map.of(
                "name", name, "channelType", "EMAIL", "subjectTemplate", "Updated {{name}}",
                "contentPayload", Map.of("html", "<p>{{name}}</p>"))), HttpStatus.OK);
        assertThat(updated.get("subjectTemplate").asText()).isEqualTo("Updated {{name}}");

        JsonNode page = data(api.get("/api/v1/templates?channel=EMAIL&size=100"), HttpStatus.OK);
        assertThat(page.get("items").findValues("id")).extracting(JsonNode::asLong).contains(id);

        data(api.delete("/api/v1/templates/" + id), HttpStatus.OK);
        assertThat(api.get("/api/v1/templates/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void previewRendersAndEscapes() {
        long id = createEmailTemplate();

        JsonNode preview = data(api.post("/api/v1/templates/" + id + "/preview",
                Map.of("params", Map.of("name", "<Ann>", "code", "42"))), HttpStatus.OK);

        assertThat(preview.get("subject").asText()).isEqualTo("Hello <Ann>");
        assertThat(preview.get("html").asText()).isEqualTo("<p>Hi &lt;Ann&gt;, your code is 42</p>");
        assertThat(preview.get("text").asText()).isEqualTo("Hi <Ann>");
        assertThat(preview.get("placeholders")).extracting(JsonNode::asText).containsExactly("name", "code");
    }

    @Test
    void campaignCrudAndValidation() {
        long templateId = createEmailTemplate();

        JsonNode created = data(api.post("/api/v1/campaigns", Map.of(
                "title", "Launch", "channelType", "EMAIL", "templateId", templateId,
                "targetAudienceType", "CSV_LIST")), HttpStatus.CREATED);
        long id = created.get("id").asLong();
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("importStatus").asText()).isEqualTo("NONE");

        ResponseEntity<JsonNode> mismatch = api.post("/api/v1/campaigns", Map.of(
                "title", "Wrong", "channelType", "LINE", "templateId", templateId, "targetAudienceType", "CSV_LIST"));
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody().get("message").asText()).contains("does not match");

        ResponseEntity<JsonNode> noMeta = api.post("/api/v1/campaigns", Map.of(
                "title", "Audience", "channelType", "EMAIL", "templateId", templateId, "targetAudienceType", "LINE_AUDIENCE_GROUP"));
        assertThat(noMeta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(api.post("/api/v1/campaigns", Map.of(
                "title", "Ghost", "channelType", "EMAIL", "templateId", 99_999_999L, "targetAudienceType", "CSV_LIST"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode detail = data(api.get("/api/v1/campaigns/" + id), HttpStatus.OK);
        assertThat(detail.get("campaign").get("title").asText()).isEqualTo("Launch");
        assertThat(detail.get("recipients").get("pending").asLong()).isZero();

        JsonNode updated = data(api.put("/api/v1/campaigns/" + id, Map.of(
                "title", "Launch v2", "channelType", "EMAIL", "templateId", templateId,
                "targetAudienceType", "CSV_LIST", "scheduledAt", "2030-01-01T00:00:00Z")), HttpStatus.OK);
        assertThat(updated.get("title").asText()).isEqualTo("Launch v2");
        assertThat(updated.get("scheduledAt").asText()).isEqualTo("2030-01-01T00:00:00Z");

        JsonNode page = data(api.get("/api/v1/campaigns?status=DRAFT&size=100"), HttpStatus.OK);
        assertThat(page.get("items").findValues("id")).extracting(JsonNode::asLong).contains(id);

        assertThat(api.delete("/api/v1/templates/" + templateId).getStatusCode())
                .as("template in use").isEqualTo(HttpStatus.CONFLICT);

        data(api.delete("/api/v1/campaigns/" + id), HttpStatus.OK);
        assertThat(api.get("/api/v1/campaigns/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        data(api.delete("/api/v1/templates/" + templateId), HttpStatus.OK);
    }

    @Test
    void lineAudienceGroupCampaignStoresMeta() {
        long lineTemplate = data(api.post("/api/v1/templates", Map.of(
                "name", unique("line"), "channelType", "LINE",
                "contentPayload", Map.of("messages", List.of(Map.of("type", "text", "text", "Hi"))))), HttpStatus.CREATED)
                .get("id").asLong();

        JsonNode created = data(api.post("/api/v1/campaigns", Map.of(
                "title", "Audience", "channelType", "LINE", "templateId", lineTemplate,
                "targetAudienceType", "LINE_AUDIENCE_GROUP",
                "targetAudienceMeta", Map.of("audienceGroupId", 4711))), HttpStatus.CREATED);

        assertThat(created.get("targetAudienceMeta").get("audienceGroupId").asLong()).isEqualTo(4711);
    }
}
