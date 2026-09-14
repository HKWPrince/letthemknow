package io.letthemknow.channel.line;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code ltk.line.*}: base URL is overridable for WireMock tests. */
@ConfigurationProperties(prefix = "ltk.line")
public record LineProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public LineProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.line.me";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
    }
}
