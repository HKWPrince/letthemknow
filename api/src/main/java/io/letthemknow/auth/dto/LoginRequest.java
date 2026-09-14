package io.letthemknow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code tenant} (tenant name) is only required when the same email exists in several tenants. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 200) String password,
        @Size(max = 100) String tenant) {
}
