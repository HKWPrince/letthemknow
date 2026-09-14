package io.letthemknow.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.letthemknow.common.ApiResponse;
import io.letthemknow.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Writes {@link ApiResponse} error bodies from security filters and handlers (401 / 403). */
@Component
public class AuthResponseWriter {

    private final ObjectMapper objectMapper;

    public AuthResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
