package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;
import com.onec.mail.MailMessage;
import com.onec.mail.MailService;

import java.time.Duration;

/**
 * Default {@link MagicLinkSender}: emails the link through the {@code onec-mail-starter}. Wired only
 * when a {@link MailService} bean is present (see the auth auto-configuration), so enabling
 * magic-link without a mail provider fails fast with a clear message rather than silently dropping
 * sign-in emails. The message is built inline (plain text + a minimal HTML part) to avoid requiring
 * the host app to register a mail template.
 */
public class MailMagicLinkSender implements MagicLinkSender {

    private final MailService mailService;
    private final OnecAuthProperties properties;

    public MailMagicLinkSender(MailService mailService, OnecAuthProperties properties) {
        this.mailService = mailService;
        this.properties = properties;
    }

    @Override
    public void send(String email, String link, Duration validity) {
        String subject = properties.getMagicLink().getSubject();
        String window = humanize(validity);
        String text = "Sign in by following this link (valid for " + window + "):\n\n"
                + link + "\n\n"
                + "If you didn't request this, you can ignore this email.";
        String html = "<p>Sign in by following this link (valid for " + window + "):</p>"
                + "<p><a href=\"" + escape(link) + "\">Sign in</a></p>"
                + "<p style=\"color:#666;font-size:13px\">If you didn't request this, you can ignore "
                + "this email.</p>";
        // Synchronous send: the user is waiting on the login screen and the link is time-sensitive,
        // so we don't route it through the async outbox relay.
        mailService.send(MailMessage.builder()
                .to(email)
                .subject(subject)
                .text(text)
                .html(html)
                .build());
    }

    /** Renders a short validity window for the email copy, e.g. {@code 15 minutes} or {@code 2 hours}. */
    private static String humanize(Duration validity) {
        long minutes = Math.max(1, validity.toMinutes());
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
