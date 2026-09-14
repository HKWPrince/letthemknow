package io.letthemknow.dispatch;

import io.letthemknow.channel.line.LineApiException;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchErrorClassifierTest {

    private final DispatchErrorClassifier classifier = new DispatchErrorClassifier();

    @ParameterizedTest
    @CsvSource({
            "429, TRANSIENT, LINE_RATE_LIMITED",
            "500, TRANSIENT, LINE_SERVER_ERROR",
            "503, TRANSIENT, LINE_SERVER_ERROR",
            "400, TERMINAL, LINE_BAD_REQUEST",
            "401, TERMINAL, LINE_AUTH",
            "403, TERMINAL, LINE_AUTH",
            "404, TERMINAL, LINE_NOT_FOUND",
            "409, TERMINAL, LINE_ERROR",
    })
    void classifiesLineStatuses(int status, ErrorClass expectedClass, String expectedCode) {
        DispatchError error = classifier.classify(new LineApiException(status, "{\"message\":\"x\"}"));

        assertThat(error.errorClass()).isEqualTo(expectedClass);
        assertThat(error.code()).isEqualTo(expectedCode);
    }

    @Test
    void smtpAuthenticationIsTerminal() {
        DispatchError error = classifier.classify(new MailAuthenticationException("535 bad credentials"));

        assertThat(error.errorClass()).isEqualTo(ErrorClass.TERMINAL);
        assertThat(error.code()).isEqualTo("SMTP_AUTH");
    }

    @Test
    void smtpPermanentAddressFailureIsTerminal() throws Exception {
        SMTPAddressFailedException addr = new SMTPAddressFailedException(
                new InternetAddress("nobody@example.com"), "RCPT TO", 550, "5.1.1 user unknown");
        DispatchError error = classifier.classify(new MailSendException(Map.of(new Object(), addr)));

        assertThat(error.errorClass()).isEqualTo(ErrorClass.TERMINAL);
        assertThat(error.code()).isEqualTo("SMTP_INVALID_ADDRESS");
        assertThat(error.message()).contains("5.1.1");
    }

    @Test
    void smtpTemporaryAddressFailureIsTransient() throws Exception {
        SMTPAddressFailedException addr = new SMTPAddressFailedException(
                new InternetAddress("busy@example.com"), "RCPT TO", 450, "4.2.1 mailbox busy");

        assertThat(classifier.classify(addr).errorClass()).isEqualTo(ErrorClass.TRANSIENT);
        assertThat(classifier.classify(addr).code()).isEqualTo("SMTP_TEMP_ADDRESS");
    }

    @Test
    void smtp4xxSendFailureIsTransientAnd5xxTerminal() {
        SMTPSendFailedException tooBusy = new SMTPSendFailedException("DATA", 421, "4.7.0 try again later", null, null, null, null);
        SMTPSendFailedException rejected = new SMTPSendFailedException("DATA", 554, "5.7.1 rejected", null, null, null, null);

        assertThat(classifier.classify(new MailSendException("send failed", tooBusy)).errorClass()).isEqualTo(ErrorClass.TRANSIENT);
        assertThat(classifier.classify(new MailSendException("send failed", tooBusy)).code()).isEqualTo("SMTP_TEMP_FAILURE");
        assertThat(classifier.classify(rejected).errorClass()).isEqualTo(ErrorClass.TERMINAL);
        assertThat(classifier.classify(rejected).code()).isEqualTo("SMTP_REJECTED");
    }

    @Test
    void networkProblemsAreTransient() {
        assertThat(classifier.classify(new MailSendException("x", new MessagingException("y", new SocketTimeoutException("read timed out")))).errorClass())
                .isEqualTo(ErrorClass.TRANSIENT);
        assertThat(classifier.classify(new MailSendException("x", new MessagingException("y", new ConnectException("refused")))).code())
                .isEqualTo("NETWORK");
        assertThat(classifier.classify(new ResourceAccessException("I/O", new IOException("reset"))).errorClass())
                .isEqualTo(ErrorClass.TRANSIENT);
    }

    @Test
    void malformedAddressAndMissingConfigAreTerminal() {
        assertThat(classifier.classify(new AddressException("Missing @")).code()).isEqualTo("SMTP_INVALID_ADDRESS");

        DispatchError config = classifier.classify(new ApiException(ErrorCode.UNPROCESSABLE, "SMTP channel is not configured"));
        assertThat(config.errorClass()).isEqualTo(ErrorClass.TERMINAL);
        assertThat(config.code()).isEqualTo("CONFIG_UNPROCESSABLE");
    }

    @Test
    void unexpectedExceptionsAreTerminalNotRetried() {
        DispatchError error = classifier.classify(new NullPointerException("bug"));

        assertThat(error.errorClass()).isEqualTo(ErrorClass.TERMINAL);
        assertThat(error.code()).isEqualTo("UNEXPECTED");
        assertThat(error.message()).isEqualTo("bug");
    }
}
