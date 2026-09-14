package io.letthemknow.channel.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

/** Sends one multipart (text + HTML) email through the tenant's cached sender. */
@Component
public class EmailSender {

    private final DynamicMailSenderFactory factory;

    public EmailSender(DynamicMailSenderFactory factory) {
        this.factory = factory;
    }

    /** @return the Message-ID assigned to the sent message. Throws {@code MailException} on failure. */
    public String send(long tenantId, String to, String subject, String html, String text) {
        SmtpSettings settings = factory.settings(tenantId);
        return send(factory.get(tenantId), settings, to, subject, html, text);
    }

    /** Sends with an explicit (uncached) sender; used for the config test email. */
    public static String send(JavaMailSenderImpl sender, SmtpSettings settings,
                              String to, String subject, String html, String text) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress(settings));
            helper.setTo(to);
            helper.setSubject(subject == null ? "" : subject);
            if (text != null && !text.isBlank()) {
                helper.setText(text, html == null ? "" : html);
            } else {
                helper.setText(html == null ? "" : html, true);
            }
            sender.send(message);
            return message.getMessageID();
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Could not build email", e);
        }
    }

    private static InternetAddress fromAddress(SmtpSettings settings)
            throws UnsupportedEncodingException, AddressException {
        String name = settings.fromName();
        return name == null || name.isBlank()
                ? new InternetAddress(settings.fromEmail())
                : new InternetAddress(settings.fromEmail(), name, "UTF-8");
    }
}
