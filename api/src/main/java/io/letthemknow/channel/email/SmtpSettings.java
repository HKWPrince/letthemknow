package io.letthemknow.channel.email;

import io.letthemknow.channel.ChannelConfig;

/** Plain SMTP parameters used to build a {@code JavaMailSender}; password is plaintext in memory only. */
public record SmtpSettings(
        String host,
        int port,
        String username,
        String password,
        boolean sslEnabled,
        String fromEmail,
        String fromName) {

    public static SmtpSettings from(ChannelConfig config) {
        return new SmtpSettings(
                config.getSmtpHost(),
                config.getSmtpPort() == null ? 587 : config.getSmtpPort(),
                config.getSmtpUsername(),
                config.getSmtpPassword(),
                config.isSmtpSslEnabled(),
                config.getSmtpFromEmail(),
                config.getSmtpFromName());
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
