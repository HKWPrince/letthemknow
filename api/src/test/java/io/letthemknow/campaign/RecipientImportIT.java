package io.letthemknow.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RecipientImportIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    RestClientSupport api;
    long campaignId;

    @BeforeEach
    void setUp() {
        api = new RestClientSupport(rest).loginAsDemoAdmin();
        long templateId = data(api.post("/api/v1/templates", Map.of(
                "name", "import-" + UUID.randomUUID().toString().substring(0, 8),
                "channelType", "EMAIL", "subjectTemplate", "Hi {{name}}",
                "contentPayload", Map.of("html", "<p>{{name}} {{code}}</p>"))), HttpStatus.CREATED).get("id").asLong();
        campaignId = data(api.post("/api/v1/campaigns", Map.of(
                "title", "Import test", "channelType", "EMAIL", "templateId", templateId,
                "targetAudienceType", "CSV_LIST")), HttpStatus.CREATED).get("id").asLong();
    }

    private ResponseEntity<JsonNode> upload(String csv) {
        ByteArrayResource file = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "recipients.csv";
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", file);
        HttpHeaders headers = api.headers();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return api.multipart("/api/v1/campaigns/" + campaignId + "/upload-recipients", new HttpEntity<>(parts, headers));
    }

    private JsonNode awaitImport(String expectedStatus) {
        return await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).until(
                () -> data(api.get("/api/v1/campaigns/" + campaignId), HttpStatus.OK),
                d -> !d.get("campaign").get("importStatus").asText().equals("IMPORTING")
                        && d.get("campaign").get("importStatus").asText().equals(expectedStatus));
    }

    @Test
    void importsTenThousandRowsDeduplicatedAndSkipsInvalid() {
        StringBuilder csv = new StringBuilder("recipient,name,code\n");
        int duplicates = 500;
        int invalid = 9;
        for (int i = 0; i < 10_000; i++) {
            int n = i < 500 ? i + 500 : i;                       // rows 0..499 duplicate rows 500..999
            boolean bad = i >= 1000 && i % 1000 == 7;            // 1007, 2007, … 9007 → 9 invalid rows
            String email = bad ? "not-an-email-" + i : "User" + n + "@Example.com";
            csv.append(email).append(",Name ").append(n).append(",C").append(n).append('\n');
        }

        ResponseEntity<JsonNode> accepted = upload(csv.toString());
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("data").get("importStatus").asText()).isEqualTo("IMPORTING");

        JsonNode detail = awaitImport("READY");
        int expected = 10_000 - duplicates - invalid;
        assertThat(detail.get("campaign").get("totalCount").asInt()).isEqualTo(expected);
        assertThat(detail.get("campaign").get("importError").isNull()).isTrue();
        assertThat(detail.get("recipients").get("pending").asLong()).isEqualTo(expected);

        JsonNode page = data(api.get("/api/v1/campaigns/" + campaignId + "/recipients?status=PENDING&size=3"), HttpStatus.OK);
        assertThat(page.get("totalElements").asLong()).isEqualTo(expected);
        assertThat(page.get("items")).hasSize(3);
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("recipientIdentifier").asText()).isEqualTo("user500@example.com");
        assertThat(first.get("payloadParams").get("name").asText()).isEqualTo("Name 500");
        assertThat(first.get("payloadParams").get("code").asText()).isEqualTo("C500");
    }

    @Test
    void reuploadReplacesRecipientsAndBadFileFails() {
        upload("recipient,name\na@x.com,A\nb@x.com,B\n");
        assertThat(awaitImport("READY").get("campaign").get("totalCount").asInt()).isEqualTo(2);

        upload("recipient\nc@x.com\n");
        JsonNode replaced = awaitImport("READY");
        assertThat(replaced.get("campaign").get("totalCount").asInt()).isEqualTo(1);
        JsonNode page = data(api.get("/api/v1/campaigns/" + campaignId + "/recipients"), HttpStatus.OK);
        assertThat(page.get("items")).extracting(n -> n.get("recipientIdentifier").asText()).containsExactly("c@x.com");

        ResponseEntity<JsonNode> empty = upload("");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        upload("recipient,name\n\"unterminated,quote\n");
        JsonNode failed = awaitImport("FAILED");
        assertThat(failed.get("campaign").get("importError").asText()).isNotBlank();
        assertThat(failed.get("recipients").get("pending").asLong()).as("rollback keeps previous list").isEqualTo(1);
    }

    @Test
    void uploadRejectedForAudienceGroupCampaigns() {
        long lineTemplate = data(api.post("/api/v1/templates", Map.of(
                "name", "line-" + UUID.randomUUID().toString().substring(0, 8), "channelType", "LINE",
                "contentPayload", Map.of("messages", List.of(Map.of("type", "text", "text", "Hi"))))), HttpStatus.CREATED)
                .get("id").asLong();
        campaignId = data(api.post("/api/v1/campaigns", Map.of(
                "title", "Audience", "channelType", "LINE", "templateId", lineTemplate,
                "targetAudienceType", "LINE_AUDIENCE_GROUP", "targetAudienceMeta", Map.of("audienceGroupId", 1))),
                HttpStatus.CREATED).get("id").asLong();

        assertThat(upload("recipient\nUaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
