package com.onec.mail.dispatch;

import com.onec.mail.MailDeliveryException;
import com.onec.mail.MailHeader;
import com.onec.mail.MailMessage;
import com.onec.mail.MailProperties;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dev dispatcher ({@code provider=file}). Writes each message as an {@code .eml}-style file to disk
 * instead of sending it, so templates can be inspected in a browser or mail client during development.
 */
public class FileMailDispatcher implements MailDispatcher {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path directory;
    private final Charset encoding;

    public FileMailDispatcher(MailProperties properties) {
        this.directory = Path.of(properties.getFile().getDirectory());
        this.encoding = Charset.forName(properties.getEncoding());
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public void dispatch(MailMessage message) {
        try {
            Files.createDirectories(directory);
            String filename = STAMP.format(LocalDateTime.now()) + "-" + UUID.randomUUID() + ".eml";
            Files.writeString(directory.resolve(filename), render(message), encoding);
        } catch (IOException e) {
            throw new MailDeliveryException("Failed to write mail to " + directory, e);
        }
    }

    private String render(MailMessage m) {
        StringBuilder sb = new StringBuilder();
        if (m.from() != null) sb.append("From: ").append(m.from()).append('\n');
        if (!m.to().isEmpty()) sb.append("To: ").append(String.join(", ", m.to())).append('\n');
        if (!m.cc().isEmpty()) sb.append("Cc: ").append(String.join(", ", m.cc())).append('\n');
        if (!m.bcc().isEmpty()) sb.append("Bcc: ").append(String.join(", ", m.bcc())).append('\n');
        if (m.replyTo() != null && !m.replyTo().isBlank()) sb.append("Reply-To: ").append(m.replyTo()).append('\n');
        sb.append("Subject: ").append(m.subject() == null ? "" : m.subject()).append('\n');
        for (MailHeader h : m.headers()) {
            sb.append(h.name()).append(": ").append(h.value()).append('\n');
        }
        sb.append("Content-Type: ").append(m.isHtml() ? "text/html" : "text/plain").append("; charset=")
                .append(encoding.name()).append('\n');
        sb.append('\n');
        sb.append(m.isHtml() ? m.html() : (m.text() == null ? "" : m.text()));
        return sb.toString();
    }
}
