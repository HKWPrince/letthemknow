package io.letthemknow.channel.line;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.letthemknow.channel.line.LineMessages.MulticastRequest;
import io.letthemknow.channel.line.LineMessages.NarrowcastProgress;
import io.letthemknow.channel.line.LineMessages.NarrowcastRequest;
import io.letthemknow.channel.line.LineMessages.PushRequest;
import io.letthemknow.channel.line.LineMessages.SendResponse;
import io.letthemknow.channel.line.LineMessages.SendResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Thin wrapper over the LINE Messaging API. Every call takes the tenant's decrypted channel access
 * token; non-2xx responses become {@link LineApiException}. Network failures surface as
 * {@code ResourceAccessException}.
 */
@Component
public class LineApiClient {

    static final String REQUEST_ID_HEADER = "X-Line-Request-Id";
    static final String RETRY_KEY_HEADER = "X-Line-Retry-Key";

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public LineApiClient(LineProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // HTTP/1.1: the LINE API does not need h2 and the JDK's cleartext h2 upgrade confuses some proxies/stubs
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new LineApiException(response.getStatusCode().value(), body);
                })
                .build();
    }

    /** POST /v2/bot/message/push */
    public SendResult push(String accessToken, PushRequest request, String retryKey) {
        return send("/v2/bot/message/push", accessToken, request, retryKey);
    }

    /** POST /v2/bot/message/multicast (up to 500 user IDs) */
    public SendResult multicast(String accessToken, MulticastRequest request, String retryKey) {
        return send("/v2/bot/message/multicast", accessToken, request, retryKey);
    }

    /** POST /v2/bot/message/narrowcast → 202; the request id is the handle for progress polling. */
    public SendResult narrowcast(String accessToken, NarrowcastRequest request, String retryKey) {
        return send("/v2/bot/message/narrowcast", accessToken, request, retryKey);
    }

    /** GET /v2/bot/message/progress/narrowcast?requestId= */
    public NarrowcastProgress narrowcastProgress(String accessToken, String requestId) {
        return client.get()
                .uri(uri -> uri.path("/v2/bot/message/progress/narrowcast").queryParam("requestId", requestId).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(NarrowcastProgress.class);
    }

    private SendResult send(String path, String accessToken, Object body, String retryKey) {
        RestClient.RequestBodySpec spec = client.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON);
        if (retryKey != null && !retryKey.isBlank()) {
            spec = spec.header(RETRY_KEY_HEADER, retryKey);
        }
        return spec.body(body).exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
                String err = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                throw new LineApiException(response.getStatusCode().value(), err);
            }
            String requestId = response.getHeaders().getFirst(REQUEST_ID_HEADER);
            List<String> ids = List.of();
            byte[] raw = response.getBody().readAllBytes();
            if (raw.length > 0) {
                SendResponse parsed = objectMapper.readValue(raw, SendResponse.class);
                if (parsed != null && parsed.sentMessages() != null) {
                    ids = parsed.sentMessages().stream().map(LineMessages.SentMessage::id).toList();
                }
            }
            return new SendResult(requestId, ids);
        });
    }
}
