package io.letthemknow.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.letthemknow.channel.line.LineApiException;
import io.letthemknow.channel.line.LineMessages;
import io.letthemknow.channel.line.LineSender;
import io.letthemknow.dispatch.DispatchErrorClassifier;
import io.letthemknow.dispatch.ErrorClass;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.RestClientSupport;
import io.letthemknow.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.letthemknow.support.RestClientSupport.data;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineApiClientIT extends IntegrationTestBase {

    /** Port must match ltk.line.base-url in application-test.yml. */
    @RegisterExtension
    static WireMockExtension line = WireMockExtension.newInstance()
            .options(wireMockConfig().port(18089))
            .build();

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TestTenants tenants;

    @Autowired
    LineSender lineSender;

    @Autowired
    DispatchErrorClassifier classifier;

    @Autowired
    ObjectMapper json;

    long tenantId;
    List<JsonNode> messages;

    @BeforeEach
    void setUp() throws Exception {
        line.resetAll();
        tenantId = tenants.demo().getId();
        RestClientSupport api = new RestClientSupport(rest).loginAsDemoAdmin();
        JsonNode saved = data(api.post("/api/v1/channels/line", Map.of(
                "channelId", "1234567890",
                "channelSecret", "line-secret",
                "channelAccessToken", "tok-123")), HttpStatus.OK);
        assertThat(saved.get("hasLineChannelToken").asBoolean()).isTrue();
        assertThat(saved.toString()).doesNotContain("tok-123").doesNotContain("line-secret");
        messages = List.of(json.readTree("{\"type\":\"text\",\"text\":\"Hello\"}"));
    }

    @Test
    void multicastSendsBearerTokenAndReturnsRequestId() {
        line.stubFor(post(urlEqualTo("/v2/bot/message/multicast"))
                .withHeader("Authorization", equalTo("Bearer tok-123"))
                .withHeader("X-Line-Retry-Key", equalTo("retry-1"))
                .withRequestBody(equalToJson("{\"to\":[\"U1\",\"U2\"],\"messages\":[{\"type\":\"text\",\"text\":\"Hello\"}]}"))
                .willReturn(aResponse().withStatus(200).withHeader("X-Line-Request-Id", "req-multicast")
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        LineMessages.SendResult result = lineSender.multicast(tenantId, List.of("U1", "U2"), messages, "retry-1");

        assertThat(result.requestId()).isEqualTo("req-multicast");
        assertThat(result.messageIds()).isEmpty();
        line.verify(1, postRequestedFor(urlEqualTo("/v2/bot/message/multicast")));
    }

    @Test
    void pushReturnsSentMessageIds() {
        line.stubFor(post(urlEqualTo("/v2/bot/message/push"))
                .willReturn(aResponse().withStatus(200).withHeader("X-Line-Request-Id", "req-push")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sentMessages\":[{\"id\":\"m-1\",\"quoteToken\":\"q\"}]}")));

        LineMessages.SendResult result = lineSender.push(tenantId, "U1", messages, null);

        assertThat(result.requestId()).isEqualTo("req-push");
        assertThat(result.messageIds()).containsExactly("m-1");
    }

    @Test
    void narrowcastThenProgress() {
        line.stubFor(post(urlEqualTo("/v2/bot/message/narrowcast"))
                .withRequestBody(equalToJson("{\"messages\":[{\"type\":\"text\",\"text\":\"Hello\"}],"
                        + "\"recipient\":{\"type\":\"audience\",\"audienceGroupId\":555}}"))
                .willReturn(aResponse().withStatus(202).withHeader("X-Line-Request-Id", "req-nc").withBody("{}")));
        line.stubFor(get(urlPathEqualTo("/v2/bot/message/progress/narrowcast"))
                .withQueryParam("requestId", equalTo("req-nc"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"phase\":\"succeeded\",\"successCount\":120,\"failureCount\":3,\"targetCount\":123,"
                                + "\"acceptedTime\":\"2026-09-10T00:00:00Z\",\"completedTime\":\"2026-09-10T00:01:00Z\"}")));

        LineMessages.SendResult result = lineSender.narrowcast(tenantId, 555L, messages, null);
        LineMessages.NarrowcastProgress progress = lineSender.progress(tenantId, result.requestId());

        assertThat(result.requestId()).isEqualTo("req-nc");
        assertThat(progress.isDone()).isTrue();
        assertThat(progress.isFailed()).isFalse();
        assertThat(progress.successCount()).isEqualTo(120);
        assertThat(progress.targetCount()).isEqualTo(123);
    }

    @Test
    void errorsMapToLineApiExceptionAndClassifier() {
        line.stubFor(post(urlEqualTo("/v2/bot/message/multicast"))
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Too many requests\"}")));

        assertThatThrownBy(() -> lineSender.multicast(tenantId, List.of("U1"), messages, null))
                .isInstanceOf(LineApiException.class)
                .satisfies(e -> {
                    LineApiException le = (LineApiException) e;
                    assertThat(le.status()).isEqualTo(429);
                    assertThat(le.body()).contains("Too many requests");
                    assertThat(classifier.classify(le).errorClass()).isEqualTo(ErrorClass.TRANSIENT);
                });

        line.stubFor(post(urlEqualTo("/v2/bot/message/push"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"The property, 'to', in the request body is invalid\"}")));

        assertThatThrownBy(() -> lineSender.push(tenantId, "bad", messages, null))
                .isInstanceOf(LineApiException.class)
                .satisfies(e -> assertThat(classifier.classify(e).errorClass()).isEqualTo(ErrorClass.TERMINAL));
    }

    @Test
    void multicastRefusesMoreThan500Recipients() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 501).mapToObj(i -> "U" + i).toList();

        assertThatThrownBy(() -> lineSender.multicast(tenantId, tooMany, messages, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
