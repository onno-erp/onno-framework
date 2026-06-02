package com.onec.mail.web;

import com.onec.mail.suppression.MailSuppressionList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Inbound delivery-event webhook (enabled via {@code onec.mail.webhook.enabled=true}). Accepts a list of
 * normalised events and feeds the {@link MailSuppressionList}: hard bounces and complaints suppress the
 * address; unsubscribes suppress it; deliveries/opens are ignored.
 *
 * <p>Providers post their own payload shapes (SES/SNS, SendGrid, ...). Translate them to this schema —
 * {@code [{"email": "x@y.com", "type": "bounce|complaint|unsubscribe|delivery", "detail": "..."}]} — in a
 * thin proxy or a provider-specific controller. This endpoint intentionally stays provider-agnostic.
 */
@RestController
public class MailEventController {

    private static final Logger log = LoggerFactory.getLogger(MailEventController.class);

    private final MailSuppressionList suppressionList;

    public MailEventController(MailSuppressionList suppressionList) {
        this.suppressionList = suppressionList;
    }

    @PostMapping(path = "${onec.mail.webhook.path:/onec/mail/events}")
    public ResponseEntity<Map<String, Integer>> ingest(@RequestBody List<MailWebhookEvent> events) {
        int suppressed = 0;
        for (MailWebhookEvent event : events) {
            if (event == null || event.email() == null || event.email().isBlank()) {
                continue;
            }
            String type = event.type() == null ? "" : event.type().toLowerCase();
            if (type.equals("bounce") || type.equals("complaint") || type.equals("unsubscribe")
                    || type.equals("spamreport")) {
                suppressionList.suppress(event.email(), type, event.detail());
                suppressed++;
            }
        }
        log.info("[mail:webhook] processed {} event(s), suppressed {}", events.size(), suppressed);
        return ResponseEntity.ok(Map.of("received", events.size(), "suppressed", suppressed));
    }

    public record MailWebhookEvent(String email, String type, String detail) {
    }
}
