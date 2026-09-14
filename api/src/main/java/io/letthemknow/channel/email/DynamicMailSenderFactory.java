package io.letthemknow.channel.email;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.letthemknow.channel.ChannelConfigRepository;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * One {@link JavaMailSenderImpl} per tenant, cached for 30 minutes and invalidated when the SMTP
 * config is saved. Senders are built from the decrypted {@code ChannelConfig}.
 */
@Component
public class DynamicMailSenderFactory {

    private static final int TIMEOUT_MS = (int) Duration.ofSeconds(15).toMillis();

    private final ChannelConfigRepository configRepository;
    private final Cache<Long, JavaMailSenderImpl> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(10_000)
            .build();

    public DynamicMailSenderFactory(ChannelConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public JavaMailSenderImpl get(long tenantId) {
        return cache.get(tenantId, this::load);
    }

    public void invalidate(long tenantId) {
        cache.invalidate(tenantId);
    }

    /** Resolves the tenant's SMTP settings, decrypted; 422 when the channel is not configured. */
    public SmtpSettings settings(long tenantId) {
        return TenantContextHolder.runAs(tenantId, () -> configRepository.findByChannelType(ChannelType.EMAIL)
                .filter(c -> c.getSmtpHost() != null && !c.getSmtpHost().isBlank())
                .map(SmtpSettings::from)
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE,
                        "SMTP channel is not configured for this tenant")));
    }

    private JavaMailSenderImpl load(long tenantId) {
        return build(settings(tenantId));
    }

    /** Builds an uncached sender; used for the synchronous test email on config save. */
    public static JavaMailSenderImpl build(SmtpSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setDefaultEncoding("UTF-8");
        if (settings.hasCredentials()) {
            sender.setUsername(settings.username());
            sender.setPassword(settings.password());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(settings.hasCredentials()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        if (settings.sslEnabled()) {
            if (settings.port() == 465) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
        }
        return sender;
    }
}
