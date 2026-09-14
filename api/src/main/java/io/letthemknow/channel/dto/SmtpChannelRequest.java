package io.letthemknow.channel.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Saving sends a synchronous test email to {@code testRecipient} (defaults to {@code fromEmail});
 * on failure nothing is persisted. {@code password} may be omitted on update to keep the stored one.
 */
public record SmtpChannelRequest(
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @Size(max = 255) String username,
        @Size(max = 4000) String password,
        @NotBlank @Email @Size(max = 255) String fromEmail,
        @Size(max = 100) String fromName,
        Boolean sslEnabled,
        @Email @Size(max = 255) String testRecipient) {

    public boolean ssl() {
        return sslEnabled == null || sslEnabled;
    }
}
