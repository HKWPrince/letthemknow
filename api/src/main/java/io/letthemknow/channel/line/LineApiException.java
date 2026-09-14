package io.letthemknow.channel.line;

/** Non-2xx answer from the LINE Messaging API; {@code body} is the raw JSON error document. */
public class LineApiException extends RuntimeException {

    private final int status;
    private final String body;

    public LineApiException(int status, String body) {
        super("LINE API returned " + status + (body == null || body.isBlank() ? "" : ": " + abbreviate(body)));
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
