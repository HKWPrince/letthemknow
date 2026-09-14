package io.letthemknow.common;

/** Error codes returned in {@link ApiResponse#code()}. HTTP status mirrors the code. */
public enum ErrorCode {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    UNPROCESSABLE(422),
    TOO_MANY_REQUESTS(429),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
