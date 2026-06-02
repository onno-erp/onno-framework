package com.onec.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-agnostic outbound mail message. Built via {@link #builder()}.
 * Body may be plain text or HTML; if HTML is set it takes precedence.
 */
public record MailMessage(
        String from,
        String replyTo,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String text,
        String html,
        List<MailAttachment> attachments,
        List<MailHeader> headers
) {

    public MailMessage {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    public boolean isHtml() {
        return html != null && !html.isBlank();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String from;
        private String replyTo;
        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> bcc = new ArrayList<>();
        private String subject;
        private String text;
        private String html;
        private final List<MailAttachment> attachments = new ArrayList<>();
        private final List<MailHeader> headers = new ArrayList<>();

        public Builder from(String v) { this.from = v; return this; }
        public Builder replyTo(String v) { this.replyTo = v; return this; }
        public Builder to(String... v) { for (String s : v) to.add(s); return this; }
        public Builder cc(String... v) { for (String s : v) cc.add(s); return this; }
        public Builder bcc(String... v) { for (String s : v) bcc.add(s); return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder text(String v) { this.text = v; return this; }
        public Builder html(String v) { this.html = v; return this; }
        public Builder attach(MailAttachment a) { this.attachments.add(a); return this; }
        public Builder header(String name, String value) { this.headers.add(new MailHeader(name, value)); return this; }

        /**
         * Adds RFC 8058 one-click unsubscribe headers. {@code uri} is an {@code https:} or {@code mailto:}
         * target; mail clients surface it as an Unsubscribe button and providers use it for list hygiene.
         */
        public Builder listUnsubscribe(String uri) {
            this.headers.add(new MailHeader("List-Unsubscribe", "<" + uri + ">"));
            this.headers.add(new MailHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
            return this;
        }

        public MailMessage build() {
            return new MailMessage(from, replyTo, to, cc, bcc, subject, text, html, attachments, headers);
        }
    }
}
