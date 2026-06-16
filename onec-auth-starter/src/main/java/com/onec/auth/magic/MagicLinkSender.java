package com.onec.auth.magic;

import java.time.Duration;

/**
 * Delivers a magic-link to a user. The default implementation ({@code MailMagicLinkSender}) sends an
 * email through the {@code onec-mail-starter}; an application can register its own bean to deliver the
 * link another way (SMS, a push channel, a custom-branded email) or to fully control the message
 * body. Keeping delivery behind this seam means the rest of the flow has no dependency on the mail
 * module.
 */
public interface MagicLinkSender {

    /**
     * @param email    the recipient address the link was requested for
     * @param link     the absolute, single-use verification URL to embed
     * @param validity how long the link remains valid, so the message can say so
     */
    void send(String email, String link, Duration validity);
}
