package io.letthemknow.auth.dto;

/** Returned once on creation; {@code key} is the plaintext and is never retrievable again. */
public record ApiKeyCreatedDto(ApiKeyDto apiKey, String key) {
}
