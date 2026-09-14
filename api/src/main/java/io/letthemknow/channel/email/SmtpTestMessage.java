package io.letthemknow.channel.email;

/**
 * The confirmation an operator receives when they save SMTP settings. It is the first email the product
 * ever sends them, so it uses the same restrained layout the starter campaign templates use: a 600px
 * table, inline styles, no images, and a mobile and dark-mode block that degrade quietly.
 *
 * <p>It echoes back what was stored so the operator can check it at a glance. The password is never
 * included, not even masked.
 */
public record SmtpTestMessage(String subject, String html, String text) {

    private static final String SUBJECT = "Your SMTP settings are working";
    private static final String ACCENT = "#1a73e8";
    private static final String GROUND = "#f4f5f7";
    private static final String HEADING = "#111418";
    private static final String BODY = "#4a5057";
    private static final String MUTED = "#8b9096";
    private static final String BORDER = "#e6e8eb";
    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    public static SmtpTestMessage forSettings(SmtpSettings settings) {
        String encryption = settings.sslEnabled()
                ? (settings.port() == 465 ? "Implicit TLS (port 465)" : "STARTTLS")
                : "None";
        String username = settings.hasCredentials() ? settings.username() : "None (unauthenticated)";
        String from = settings.fromName() == null || settings.fromName().isBlank()
                ? settings.fromEmail()
                : settings.fromName() + " <" + settings.fromEmail() + ">";

        String rows = row("Server", settings.host() + ":" + settings.port())
                + row("Encryption", encryption)
                + row("Username", username)
                + row("From", from);

        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="x-apple-disable-message-reformatting">
                <meta name="color-scheme" content="light dark">
                <meta name="supported-color-schemes" content="light dark">
                <title>%s</title>
                <style>
                  @media only screen and (max-width:600px) {
                    .ltk-wrap { width:100%% !important; }
                    .ltk-pad { padding:28px 22px !important; }
                  }
                  @media (prefers-color-scheme: dark) {
                    .ltk-body { background:#191c1e !important; }
                    .ltk-card { background:#24272a !important; border-color:#34383b !important; }
                    .ltk-h { color:#f1f3f4 !important; }
                    .ltk-p, .ltk-td { color:#c8ccce !important; }
                    .ltk-muted { color:#9aa0a6 !important; }
                    .ltk-box { background:#2c3033 !important; }
                  }
                </style>
                </head>
                <body class="ltk-body" style="margin:0;padding:0;background:%s;">
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;">Your test message arrived, so campaigns will send from this address.</div>
                  <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%%" style="background:%s;">
                    <tr>
                      <td align="center" style="padding:32px 12px;">
                        <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="600" class="ltk-wrap" style="width:600px;max-width:600px;">
                          <tr>
                            <td class="ltk-h" style="padding:0 4px 16px;font-family:%s;font-size:15px;font-weight:700;letter-spacing:.3px;color:%s;">LetThemKnow</td>
                          </tr>
                          <tr>
                            <td class="ltk-card" style="background:#ffffff;border:1px solid %s;border-radius:10px;">
                              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%%">
                                <tr>
                                  <td class="ltk-pad" style="padding:36px 40px;">
                                    <p style="margin:0 0 10px;font-family:%s;font-size:12px;font-weight:600;letter-spacing:.8px;text-transform:uppercase;color:%s;">Channel test</p>
                                    <h1 class="ltk-h" style="margin:0 0 18px;font-family:%s;font-size:24px;line-height:1.3;font-weight:700;color:%s;">%s</h1>
                                    <p class="ltk-p" style="margin:0 0 16px;font-family:%s;font-size:16px;line-height:1.65;color:%s;">This message was sent through the SMTP server you just saved. Because it arrived, the settings are correct and your campaigns will send from this address.</p>
                                    <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%%" class="ltk-box" style="margin:6px 0 4px;padding:6px 18px;background:%s;border-radius:6px;">%s
                                    </table>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td class="ltk-muted" style="padding:20px 4px 0;font-family:%s;font-size:12px;line-height:1.6;color:%s;">
                              You received this because someone saved SMTP settings for your tenant.<br>
                              Sent by LetThemKnow
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>"""
                .formatted(SUBJECT, GROUND, GROUND, FONT, HEADING, BORDER, FONT, ACCENT, FONT, HEADING, SUBJECT,
                        FONT, BODY, GROUND, rows, FONT, MUTED);

        String text = """
                %s

                This message was sent through the SMTP server you just saved. Because it arrived, the
                settings are correct and your campaigns will send from this address.

                Server:     %s:%d
                Encryption: %s
                Username:   %s
                From:       %s

                —
                You received this because someone saved SMTP settings for your tenant.
                Sent by LetThemKnow"""
                .formatted(SUBJECT, settings.host(), settings.port(), encryption, username, from);

        return new SmtpTestMessage(SUBJECT, html, text);
    }

    private static String row(String label, String value) {
        return """

                                      <tr>
                                        <td class="ltk-td" style="padding:9px 0;font-family:%s;font-size:14px;color:%s;">%s</td>
                                        <td class="ltk-td" align="right" style="padding:9px 0;font-family:%s;font-size:14px;color:%s;">%s</td>
                                      </tr>"""
                .formatted(FONT, MUTED, label, FONT, HEADING, escape(value));
    }

    /** Host, username and from-address are operator input; they must not be able to break the markup. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
