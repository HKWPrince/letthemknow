package io.letthemknow.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.letthemknow.channel.email.EmailSender;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import io.letthemknow.support.TestTenants;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;

class ChannelSmtpIT extends IntegrationTestBase {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("mailer", "secret"))
            .withPerMethodLifecycle(false);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TestTenants tenants;

    @Autowired
    EmailSender emailSender;

    RestClientSupport api;

    @BeforeEach
    void setUp() throws Exception {
        api = new RestClientSupport(rest).loginAsDemoAdmin();
        greenMail.purgeEmailFromAllMailboxes();
    }

    private Map<String, Object> smtpBody(int port) {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "localhost");
        body.put("port", port);
        body.put("username", "mailer");
        body.put("password", "secret");
        body.put("fromEmail", "noreply@demo.local");
        body.put("fromName", "Demo Sender");
        body.put("sslEnabled", false);
        body.put("testRecipient", "ops@demo.local");
        return body;
    }

    @Test
    void savingSmtpConfigSendsTestEmailAndMasksSecrets() throws Exception {
        JsonNode saved = data(api.post("/api/v1/channels/smtp", smtpBody(ServerSetupTest.SMTP.getPort())), HttpStatus.OK);

        assertThat(saved.get("channelType").asText()).isEqualTo("EMAIL");
        assertThat(saved.get("hasSmtpPassword").asBoolean()).isTrue();
        assertThat(saved.toString()).doesNotContain("secret");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("ops@demo.local");
        assertThat(received[0].getSubject()).isEqualTo("Your SMTP settings are working");
        assertThat(received[0].getFrom()[0].toString()).contains("noreply@demo.local");

        JsonNode list = data(api.get("/api/v1/channels"), HttpStatus.OK);
        assertThat(list.findValues("channelType")).extracting(JsonNode::asText).contains("EMAIL");
        assertThat(list.toString()).doesNotContain("secret");
    }

    @Test
    void failedTestEmailReturns422AndPersistsNothing() throws Exception {
        data(api.post("/api/v1/channels/smtp", smtpBody(ServerSetupTest.SMTP.getPort())), HttpStatus.OK);
        greenMail.purgeEmailFromAllMailboxes();

        Map<String, Object> broken = smtpBody(1);
        broken.put("host", "127.0.0.1");
        broken.put("fromName", "Broken");
        ResponseEntity<JsonNode> res = api.post("/api/v1/channels/smtp", broken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().get("code").asInt()).isEqualTo(422);
        assertThat(res.getBody().get("message").asText()).contains("SMTP test email failed");

        JsonNode list = data(api.get("/api/v1/channels"), HttpStatus.OK);
        JsonNode email = list.findParents("channelType").stream()
                .filter(n -> n.get("channelType").asText().equals("EMAIL")).findFirst().orElseThrow();
        assertThat(email.get("smtpFromName").asText()).isEqualTo("Demo Sender");
        assertThat(email.get("smtpPort").asInt()).isEqualTo(ServerSetupTest.SMTP.getPort());
    }

    @Test
    void wrongCredentialsAreRejectedBeforeSaving() {
        Map<String, Object> bad = smtpBody(ServerSetupTest.SMTP.getPort());
        bad.put("password", "wrong");

        ResponseEntity<JsonNode> res = api.post("/api/v1/channels/smtp", bad);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void dynamicSenderDeliversMultipartMail() throws Exception {
        data(api.post("/api/v1/channels/smtp", smtpBody(ServerSetupTest.SMTP.getPort())), HttpStatus.OK);
        greenMail.purgeEmailFromAllMailboxes();

        String messageId = emailSender.send(tenants.demo().getId(), "ann@example.com",
                "Hello Ann", "<p>Hi <b>Ann</b></p>", "Hi Ann");

        assertThat(messageId).isNotBlank();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Hello Ann");
        String raw = GreenMailUtil.getWholeMessage(received[0]);
        assertThat(raw).contains("multipart/alternative").contains("Hi Ann").contains("<b>Ann</b>");
    }
}
