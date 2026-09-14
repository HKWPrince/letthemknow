package io.letthemknow.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** JSON (de)serialisation of {@link DispatchMessage} for Redis streams and queues. */
@Component
public class DispatchMessageCodec {

    private final ObjectMapper objectMapper;

    public DispatchMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(DispatchMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise dispatch message", e);
        }
    }

    public DispatchMessage decode(String json) {
        try {
            return objectMapper.readValue(json, DispatchMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed dispatch message: " + json, e);
        }
    }
}
