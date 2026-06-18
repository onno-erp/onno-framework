package su.onno.mail.dispatch;

import su.onno.mail.MailAttachment;
import su.onno.mail.MailDeliveryException;
import su.onno.mail.MailHeader;
import su.onno.mail.MailMessage;
import su.onno.mail.MailProperties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public class SmtpMailDispatcher implements MailDispatcher {

    private final JavaMailSender sender;
    private final MailProperties properties;

    public SmtpMailDispatcher(JavaMailSender sender, MailProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void dispatch(MailMessage message) {
        MimeMessage mime = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime,
                    !message.attachments().isEmpty(),
                    Charset.forName(properties.getEncoding()).name());

            String from = message.from() != null ? message.from() : properties.getDefaultFrom();
            if (from == null) {
                throw new MailDeliveryException("No 'from' address and no onno.mail.default-from configured");
            }
            helper.setFrom(from);
            if (message.replyTo() != null && !message.replyTo().isBlank()) {
                helper.setReplyTo(message.replyTo());
            }
            if (!message.to().isEmpty()) helper.setTo(message.to().toArray(new String[0]));
            if (!message.cc().isEmpty()) helper.setCc(message.cc().toArray(new String[0]));
            if (!message.bcc().isEmpty()) helper.setBcc(message.bcc().toArray(new String[0]));
            helper.setSubject(message.subject() != null ? message.subject() : "");

            if (message.isHtml()) {
                helper.setText(message.text() != null ? message.text() : "", message.html());
            } else {
                helper.setText(message.text() != null ? message.text() : "");
            }

            for (MailAttachment a : message.attachments()) {
                helper.addAttachment(
                        a.filename(),
                        new org.springframework.core.io.ByteArrayResource(a.content()) {
                            @Override
                            public String getFilename() { return a.filename(); }
                        },
                        a.contentType());
            }

            for (MailHeader h : message.headers()) {
                mime.setHeader(h.name(), h.value());
            }
        } catch (MessagingException e) {
            throw new MailDeliveryException("Failed to build MIME message", e);
        }

        try {
            sender.send(mime);
        } catch (org.springframework.mail.MailException e) {
            throw new MailDeliveryException("SMTP send failed", e);
        }
    }
}
