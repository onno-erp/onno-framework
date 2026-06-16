package com.onec.auth.magic;

import com.onec.auth.OnecAuthProperties;
import com.onec.mail.MailMessage;
import com.onec.mail.MailService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailMagicLinkSenderTest {

    @Test
    void sendsAnEmailCarryingTheLinkSubjectAndValidity() {
        MailService mailService = mock(MailService.class);
        OnecAuthProperties properties = new OnecAuthProperties();
        properties.getMagicLink().setSubject("Sign in to Acme");

        new MailMagicLinkSender(mailService, properties).send(
                "alice@example.com",
                "https://app.example.com/api/auth/magic/verify?token=abc123",
                Duration.ofMinutes(15));

        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(sent.capture());

        MailMessage message = sent.getValue();
        assertThat(message.to()).containsExactly("alice@example.com");
        assertThat(message.subject()).isEqualTo("Sign in to Acme");
        assertThat(message.text()).contains("https://app.example.com/api/auth/magic/verify?token=abc123");
        assertThat(message.text()).contains("15 minutes");
        assertThat(message.html()).contains("href=\"https://app.example.com/api/auth/magic/verify?token=abc123\"");
        assertThat(message.isHtml()).isTrue();
    }

    @Test
    void rendersHourValidityWindow() {
        MailService mailService = mock(MailService.class);
        new MailMagicLinkSender(mailService, new OnecAuthProperties())
                .send("a@b.c", "https://x/verify?token=t", Duration.ofHours(1));

        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).send(sent.capture());
        assertThat(sent.getValue().text()).contains("1 hour");
    }
}
