package io.letthemknow.channel;

import io.letthemknow.channel.dto.ChannelConfigDto;
import io.letthemknow.channel.dto.LineChannelRequest;
import io.letthemknow.channel.dto.SmtpChannelRequest;
import io.letthemknow.channel.email.DynamicMailSenderFactory;
import io.letthemknow.channel.email.EmailSender;
import io.letthemknow.channel.email.SmtpSettings;
import io.letthemknow.channel.email.SmtpTestMessage;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChannelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfigService.class);

    private final ChannelConfigRepository repository;
    private final ChannelConfigMapper mapper;
    private final DynamicMailSenderFactory mailSenderFactory;

    public ChannelConfigService(ChannelConfigRepository repository, ChannelConfigMapper mapper,
                                DynamicMailSenderFactory mailSenderFactory) {
        this.repository = repository;
        this.mapper = mapper;
        this.mailSenderFactory = mailSenderFactory;
    }

    @Transactional(readOnly = true)
    public List<ChannelConfigDto> list() {
        return repository.findAllByOrderByChannelTypeAsc().stream().map(mapper::toDto).toList();
    }

    @Transactional
    public ChannelConfigDto upsertLine(LineChannelRequest request) {
        ChannelConfig config = repository.findByChannelType(ChannelType.LINE)
                .orElseGet(() -> new ChannelConfig(ChannelType.LINE));
        config.setLineChannelId(request.channelId().trim());
        if (present(request.channelSecret())) {
            config.setLineChannelSecret(request.channelSecret().trim());
        }
        if (present(request.channelAccessToken())) {
            config.setLineChannelToken(request.channelAccessToken().trim());
        }
        if (config.getLineChannelSecret() == null || config.getLineChannelToken() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "channelSecret and channelAccessToken are required");
        }
        return mapper.toDto(repository.save(config));
    }

    /** Sends a test email first; a failure yields 422 and nothing is persisted. */
    @Transactional
    public ChannelConfigDto upsertSmtp(SmtpChannelRequest request) {
        ChannelConfig config = repository.findByChannelType(ChannelType.EMAIL)
                .orElseGet(() -> new ChannelConfig(ChannelType.EMAIL));

        String password = present(request.password()) ? request.password() : config.getSmtpPassword();
        String username = present(request.username()) ? request.username().trim() : null;
        if (username != null && password == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "password is required when username is set");
        }
        SmtpSettings candidate = new SmtpSettings(
                request.host().trim(), request.port(), username, username == null ? null : password,
                request.ssl(), request.fromEmail().trim(),
                present(request.fromName()) ? request.fromName().trim() : null);

        String to = present(request.testRecipient()) ? request.testRecipient().trim() : candidate.fromEmail();
        sendTestEmail(candidate, to);

        config.setSmtpHost(candidate.host());
        config.setSmtpPort(candidate.port());
        config.setSmtpUsername(candidate.username());
        config.setSmtpPassword(candidate.password());
        config.setSmtpFromEmail(candidate.fromEmail());
        config.setSmtpFromName(candidate.fromName());
        config.setSmtpSslEnabled(candidate.sslEnabled());
        ChannelConfig saved = repository.save(config);
        mailSenderFactory.invalidate(TenantContextHolder.require());
        return mapper.toDto(saved);
    }

    private void sendTestEmail(SmtpSettings settings, String to) {
        try {
            SmtpTestMessage message = SmtpTestMessage.forSettings(settings);
            EmailSender.send(DynamicMailSenderFactory.build(settings), settings, to,
                    message.subject(), message.html(), message.text());
        } catch (MailException e) {
            log.info("SMTP test email failed for tenant {}: {}", TenantContextHolder.get().orElse(null), e.getMessage());
            throw new ApiException(ErrorCode.UNPROCESSABLE, "SMTP test email failed: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
