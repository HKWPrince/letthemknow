package io.letthemknow.dispatch;

import io.letthemknow.channel.line.LineApiException;
import io.letthemknow.common.ApiException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Maps delivery exceptions to {@link DispatchError} (CLAUDE.md §3.7):
 * <ul>
 *   <li>TRANSIENT: SMTP connect/read timeouts and 4xx replies (421/450/451…), LINE 429 and 5xx, network I/O</li>
 *   <li>TERMINAL: invalid address / 5xx SMTP reply, SMTP auth failure, LINE 400/401/403/404, unconfigured channel,
 *       and any unexpected non-I/O exception (deterministic bugs should not burn retries)</li>
 * </ul>
 */
@Component
public class DispatchErrorClassifier {

    public DispatchError classify(Throwable throwable) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = throwable; t != null && visited.add(t); t = t.getCause()) {
            DispatchError specific = classifyOne(t);
            if (specific != null) {
                return specific;
            }
            if (t instanceof MailSendException mse) {
                for (Exception nested : mse.getMessageExceptions()) {
                    DispatchError nestedError = classify(nested);
                    if (nestedError != null) {
                        return nestedError;
                    }
                }
            }
        }
        return DispatchError.terminal("UNEXPECTED", describe(throwable));
    }

    private DispatchError classifyOne(Throwable t) {
        if (t instanceof LineApiException line) {
            return classifyLine(line);
        }
        if (t instanceof MailAuthenticationException) {
            return DispatchError.terminal("SMTP_AUTH", describe(t));
        }
        if (t instanceof SMTPAddressFailedException af) {
            return af.getReturnCode() >= 400 && af.getReturnCode() < 500
                    ? DispatchError.transientError("SMTP_TEMP_ADDRESS", describe(t))
                    : DispatchError.terminal("SMTP_INVALID_ADDRESS", describe(t));
        }
        if (t instanceof SMTPSendFailedException sf) {
            return sf.getReturnCode() >= 400 && sf.getReturnCode() < 500
                    ? DispatchError.transientError("SMTP_TEMP_FAILURE", describe(t))
                    : DispatchError.terminal("SMTP_REJECTED", describe(t));
        }
        if (t instanceof SendFailedException || t instanceof AddressException) {
            return DispatchError.terminal("SMTP_INVALID_ADDRESS", describe(t));
        }
        if (t instanceof SocketTimeoutException || t instanceof TimeoutException
                || t instanceof ConnectException || t instanceof UnknownHostException) {
            return DispatchError.transientError("NETWORK", describe(t));
        }
        if (t instanceof ResourceAccessException || t instanceof IOException) {
            return DispatchError.transientError("NETWORK", describe(t));
        }
        if (t instanceof ApiException api) {
            return DispatchError.terminal("CONFIG_" + api.code().name(), api.getMessage());
        }
        return null;
    }

    private DispatchError classifyLine(LineApiException e) {
        int s = e.status();
        if (s == 429) {
            return DispatchError.transientError("LINE_RATE_LIMITED", e.getMessage());
        }
        if (s >= 500) {
            return DispatchError.transientError("LINE_SERVER_ERROR", e.getMessage());
        }
        if (s == 401 || s == 403) {
            return DispatchError.terminal("LINE_AUTH", e.getMessage());
        }
        if (s == 400) {
            return DispatchError.terminal("LINE_BAD_REQUEST", e.getMessage());
        }
        if (s == 404) {
            return DispatchError.terminal("LINE_NOT_FOUND", e.getMessage());
        }
        return DispatchError.terminal("LINE_ERROR", e.getMessage());
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        String text = message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
}
