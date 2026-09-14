package io.letthemknow.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform envelope for every controller response: {@code { code, message, data }}.
 * {@code code} mirrors the HTTP status (200 on success, otherwise the {@link ErrorCode} value).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    /** Pair with {@code @ResponseStatus(HttpStatus.CREATED)} so the HTTP status mirrors the code. */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "Created", data);
    }

    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return new ApiResponse<>(code.status(), message, null);
    }
}
