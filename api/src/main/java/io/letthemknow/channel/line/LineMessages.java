package io.letthemknow.channel.line;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Typed request/response records for the LINE Messaging API subset used by LetThemKnow. */
public final class LineMessages {

    private LineMessages() {}

    public record PushRequest(String to, List<JsonNode> messages) {}

    public record MulticastRequest(List<String> to, List<JsonNode> messages) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NarrowcastRequest(List<JsonNode> messages, Recipient recipient) {}

    /** {@code {"type":"audience","audienceGroupId":123}} */
    public record Recipient(String type, long audienceGroupId) {
        public static Recipient audience(long audienceGroupId) {
            return new Recipient("audience", audienceGroupId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SentMessage(String id, String quoteToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(List<SentMessage> sentMessages) {}

    /** Result of a push/multicast/narrowcast call; {@code requestId} comes from {@code X-Line-Request-Id}. */
    public record SendResult(String requestId, List<String> messageIds) {}

    /** {@code phase}: waiting | sending | succeeded | failed */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NarrowcastProgress(
            String phase,
            Integer successCount,
            Integer failureCount,
            Integer targetCount,
            String failedDescription,
            Integer errorCode,
            String acceptedTime,
            String completedTime) {

        public boolean isDone() {
            return "succeeded".equals(phase) || "failed".equals(phase);
        }

        public boolean isFailed() {
            return "failed".equals(phase);
        }
    }
}
