package com.onec.mail;

import com.onec.mail.dispatch.MailDispatcher;
import com.onec.mail.outbox.MailOutbox;
import com.onec.mail.suppression.MailSuppressionList;
import com.onec.mail.template.MailRenderer;
import com.onec.mail.template.MailTemplateDescriptor;
import com.onec.mail.template.MailTemplateRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * High-level mail facade. Three modes of use:
 * <ul>
 *     <li>{@link #send(MailMessage)} - synchronous dispatch via the active provider.</li>
 *     <li>{@link #queue(MailMessage)} - durable queue via the mail outbox; relayed asynchronously with retry.</li>
 *     <li>{@link #send(Object, String, String...)} - render a registered {@code MailTemplate}
 *         bound to {@code target.getClass()} and dispatch (queued or direct based on config).</li>
 * </ul>
 * Default routing of {@link #send(Object, String, String...)} is controlled by {@code onec.mail.use-outbox}.
 *
 * <p>Recipients on the {@link MailSuppressionList} (hard bounces, complaints, unsubscribes) are dropped
 * before dispatch; a message left with no recipients is skipped entirely.
 */
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailDispatcher dispatcher;
    private final MailTemplateRegistry templates;
    private final MailRenderer renderer;
    private final MailProperties properties;
    private final MailOutbox outbox;
    private final MailSuppressionList suppressionList;
    private final ObjectMapper objectMapper;

    public MailService(MailDispatcher dispatcher,
                       MailTemplateRegistry templates,
                       MailRenderer renderer,
                       MailProperties properties,
                       MailOutbox outbox,
                       MailSuppressionList suppressionList,
                       ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.templates = templates;
        this.renderer = renderer;
        this.properties = properties;
        this.outbox = outbox;
        this.suppressionList = suppressionList;
        this.objectMapper = objectMapper;
    }

    /** Synchronous dispatch via the configured provider. Suppressed recipients are removed first. */
    public void send(MailMessage message) {
        MailMessage filtered = applySuppression(message);
        if (filtered == null) {
            return;
        }
        dispatcher.dispatch(filtered);
    }

    /** Durable queue. Picked up by the relay and dispatched asynchronously with retry/backoff. */
    public UUID queue(MailMessage message) {
        return queue(message, null);
    }

    /**
     * Durable queue with an idempotency key: enqueuing twice with the same key dispatches once.
     * Use a stable business id (e.g. {@code "booking-confirmed:" + booking.number}) to make retries safe.
     */
    public UUID queue(MailMessage message, String idempotencyKey) {
        if (outbox == null) {
            throw new IllegalStateException(
                    "MailOutbox is not available; either disable onec.mail.use-outbox or configure a DataSource");
        }
        MailMessage filtered = applySuppression(message);
        if (filtered == null) {
            return null;
        }
        try {
            String payload = objectMapper.writeValueAsString(filtered);
            return outbox.enqueue(payload, dispatcher.name(), idempotencyKey);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MailMessage", e);
        }
    }

    /** Render a template registered against {@code target.getClass()} and dispatch. */
    public void send(Object target, String templateName, Map<String, Object> extras, String... recipients) {
        MailMessage message = build(target, templateName, extras, recipients);
        if (properties.isUseOutbox() && outbox != null) {
            queue(message);
        } else {
            send(message);
        }
    }

    public void send(Object target, String templateName, String... recipients) {
        send(target, templateName, Map.of(), recipients);
    }

    public UUID queue(Object target, String templateName, Map<String, Object> extras, String... recipients) {
        return queue(build(target, templateName, extras, recipients));
    }

    public UUID queue(Object target, String templateName, String idempotencyKey,
                      Map<String, Object> extras, String... recipients) {
        return queue(build(target, templateName, extras, recipients), idempotencyKey);
    }

    private MailMessage build(Object target, String templateName,
                              Map<String, Object> extras, String[] recipients) {
        MailTemplateDescriptor descriptor = templates.find(target.getClass(), templateName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No mail template '" + templateName + "' on " + target.getClass().getName()));
        MailRenderer.Rendered rendered = renderer.render(descriptor, target, extras);

        MailMessage.Builder b = MailMessage.builder()
                .from(properties.getDefaultFrom())
                .to(recipients)
                .subject(rendered.subject());
        if (descriptor.replyTo() != null && !descriptor.replyTo().isBlank()) {
            b.replyTo(descriptor.replyTo());
        }
        if (rendered.html()) {
            b.html(rendered.body());
            if (properties.isDerivePlainText()) {
                b.text(HtmlToText.convert(rendered.body()));
            }
        } else {
            b.text(rendered.body());
        }
        return b.build();
    }

    /** Removes suppressed addresses. Returns null when nothing is left to send. */
    private MailMessage applySuppression(MailMessage message) {
        if (suppressionList == null) {
            return message;
        }
        List<String> to = keepAllowed(message.to());
        List<String> cc = keepAllowed(message.cc());
        List<String> bcc = keepAllowed(message.bcc());
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            log.debug("[mail] all recipients suppressed; skipping message subject=\"{}\"", message.subject());
            return null;
        }
        if (to.size() == message.to().size()
                && cc.size() == message.cc().size()
                && bcc.size() == message.bcc().size()) {
            return message;
        }
        return new MailMessage(message.from(), message.replyTo(), to, cc, bcc,
                message.subject(), message.text(), message.html(),
                message.attachments(), message.headers());
    }

    private List<String> keepAllowed(List<String> addresses) {
        List<String> kept = new ArrayList<>(addresses.size());
        for (String a : addresses) {
            if (!suppressionList.isSuppressed(a)) {
                kept.add(a);
            }
        }
        return kept;
    }
}
