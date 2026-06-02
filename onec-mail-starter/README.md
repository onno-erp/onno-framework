# onec-mail-starter

Spring Boot starter for transactional email in a oneC application. It renders Thymeleaf templates
declared on your domain classes, dispatches them through a pluggable provider (SMTP, a universal HTTP
REST adapter, or dev-only log/file sinks), and — when a `DataSource` is present — durably queues mail
in an outbox that a scheduled relay drains with retry/backoff and per-recipient suppression.

It builds on Spring's own mail support: SMTP transport is plain Spring Boot
`spring-boot-starter-mail` (`JavaMailSender`), so **host/port/credentials/TLS are configured with the
standard `spring.mail.*` keys**, not under `onec.mail.*`. The `onec.mail.*` namespace only governs
provider selection, templating, the outbox, and the dev/web extras.

## Enabling

Auto-configuration kicks in when `JavaMailSender` is on the classpath (it is, via this starter) and
`onec.mail.enabled` is not `false` (**default `true` — enabled out of the box**). Point the SMTP
transport at your server with Spring's keys and set a default From:

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: ${SMTP_USER}
    password: ${SMTP_PASS}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

onec:
  mail:
    default-from: "oneC <no-reply@example.com>"
```

That exposes `MailService`, a `MailTemplateRegistry` (populated by scanning your base packages for
`@MailTemplate`), a `MailRenderer`, and the dispatcher beans. With a `DataSource` on the context you
also get a `MailOutbox`, a `MailSuppressionList`, and the scheduled relay.

### Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onec.mail.enabled` | `true` | Master switch for the auto-configuration. |
| `onec.mail.provider` | `smtp` | Active dispatcher, chosen by its `name()`: `smtp`, `http`, `log`, `file`, or `failover`. |
| `onec.mail.default-from` | — | From: address used when a `MailMessage` doesn't set one. SMTP send fails if neither is set. |
| `onec.mail.base-packages` | app's auto-config packages | Packages scanned for `@MailTemplate`. |
| `onec.mail.use-outbox` | `true` | When true, the template `send(...)` overloads queue via the outbox (async); when false they dispatch synchronously. |
| `onec.mail.relay-batch-size` | `50` | Messages claimed per relay run. |
| `onec.mail.encoding` | `UTF-8` | Charset for rendering and MIME. |
| `onec.mail.derive-plain-text` | `true` | When a template is HTML-only, derive a plain-text alternative so the mail is multipart. |
| `onec.mail.relay.enabled` | `true` | Scheduled outbox relay (needs an outbox / `DataSource`). |
| `onec.mail.relay.interval-ms` | `30000` | Delay between relay runs. |
| `onec.mail.relay.max-attempts` | `5` | Delivery attempts before a message is marked `FAILED`. |
| `onec.mail.relay.lease-timeout-ms` | `300000` | How long a claimed (`SENDING`) message may sit before another worker reclaims it. |
| `onec.mail.file.directory` | `build/mail` | Output dir for the `file` dev dispatcher (`.eml` files). |
| `onec.mail.http.url` | — | Endpoint the `http` provider POSTs to. |
| `onec.mail.http.method` | `POST` | HTTP method for the `http` provider. |
| `onec.mail.http.headers` | `{}` | Static headers per request (e.g. `Authorization: Bearer ...`). |
| `onec.mail.http.body-template` | — | Thymeleaf (TEXT-mode) template that renders the provider's JSON body. `MailMessage` is `msg`. |
| `onec.mail.http.success-status-max` | `299` | Highest HTTP status still treated as success. |
| `onec.mail.failover.providers` | `[]` | Ordered provider names tried in turn when `provider=failover`. Required in that mode. |
| `onec.mail.preview.enabled` | `false` | Dev-only template-preview endpoints. Never enable in production. |
| `onec.mail.preview.path` | `/onec/mail/preview` | Base path for the preview endpoints. |
| `onec.mail.webhook.enabled` | `false` | Inbound delivery-event webhook that feeds the suppression list. |
| `onec.mail.webhook.path` | `/onec/mail/events` | Path the provider posts events to. |

## Templates

Attach a template to a domain/document class with `@MailTemplate` (repeatable). The rendered subject is
itself a Thymeleaf expression, and the body lives at `classpath:/mail/{name}.html` unless `template()`
overrides it. Inside both, the target is exposed as `doc` (and `self`); extras you pass become top-level
variables. Bodies may pull in shared layouts/fragments via `th:insert` / `th:replace` resolved against
`classpath:/mail/`.

```java
@MailTemplate(
        name = "booking-confirmed",
        subject = "Booking #[[${doc.ref}]] confirmed",
        html = true,                              // default; body sent as HTML
        replyTo = "support@example.com")          // optional default reply-to
public class Booking {
    public String getRef() { return "B-1234"; }
    // ...
}
```

```html
<!-- src/main/resources/mail/booking-confirmed.html -->
<div th:replace="~{layouts/base :: html(~{::content})}">
  <div th:fragment="content">
    <p>Booking <span th:text="${doc.ref}">B-0</span> is confirmed.</p>
  </div>
</div>
```

## Usage

Inject `MailService`. Two ways to address it: by template (rendered from a target instance) or by
building a `MailMessage` directly.

```java
@Service
class Notifications {
    private final MailService mail;

    Notifications(MailService mail) { this.mail = mail; }

    void confirm(Booking booking) {
        // Render the "booking-confirmed" template registered on Booking.class and send.
        // Routed via the outbox (async) or synchronously per onec.mail.use-outbox.
        mail.send(booking, "booking-confirmed", "guest@example.com");

        // With extra template variables:
        mail.send(booking, "booking-confirmed",
                Map.of("couponCode", "WELCOME10"), "guest@example.com");

        // Durable queue with an idempotency key — enqueuing twice with the same key sends once:
        UUID id = mail.queue(booking, "booking-confirmed",
                "booking-confirmed:" + booking.getRef(), Map.of(), "guest@example.com");
    }

    void raw() {
        // Hand-built message (attachments, cc/bcc, custom headers, one-click unsubscribe):
        MailMessage msg = MailMessage.builder()
                .from("no-reply@example.com")            // optional; falls back to default-from
                .to("guest@example.com")
                .cc("ops@example.com")
                .subject("Your invoice")
                .html("<p>See attached.</p>")            // text(...) for plain text
                .attach(new MailAttachment("invoice.pdf", "application/pdf", pdfBytes))
                .header("X-Campaign", "invoices")
                .listUnsubscribe("https://example.com/u/abc")  // RFC 8058 one-click
                .build();

        mail.send(msg);            // synchronous dispatch via the active provider
        mail.queue(msg);           // durable queue -> relayed asynchronously with retry
    }
}
```

Service API (real signatures):

```java
void send(MailMessage message);
UUID queue(MailMessage message);
UUID queue(MailMessage message, String idempotencyKey);

void send(Object target, String templateName, String... recipients);
void send(Object target, String templateName, Map<String,Object> extras, String... recipients);
UUID queue(Object target, String templateName, Map<String,Object> extras, String... recipients);
UUID queue(Object target, String templateName, String idempotencyKey,
           Map<String,Object> extras, String... recipients);
```

`MailMessage.builder()` supports `from`, `replyTo`, `to`/`cc`/`bcc` (varargs), `subject`, `text`,
`html`, `attach(MailAttachment)`, `header(name, value)`, and `listUnsubscribe(uri)`.

## Providers

The active provider is `onec.mail.provider`. All dispatchers are wired as candidates and the named one
is selected:

- **`smtp`** — Spring's `JavaMailSender`. Multipart when HTML + text or attachments are present.
- **`http`** — universal REST adapter: POSTs a Thymeleaf-rendered JSON body (`onec.mail.http.body-template`)
  to `onec.mail.http.url`, so a SendGrid/SES/etc. API is onboarded with config only. Requires `spring-web`
  (`RestClient`) on the classpath. In templates, `json.str(...)` emits a correctly-escaped JSON literal.
- **`log`** — logs the message instead of sending (local/test).
- **`file`** — writes each message as an `.eml` file under `onec.mail.file.directory` (dev/preview).
- **`failover`** — tries `onec.mail.failover.providers` in order, advancing only on failure; throws only
  if every delegate fails. Requires a non-empty provider list.

## Outbox, relay & suppression (needs a DataSource)

When a `DataSource` is on the context the starter creates and auto-migrates two tables,
`onec_mail_outbox` and `onec_mail_suppression`, via JDBI.

- **Outbox.** `queue(...)` serialises the message to `onec_mail_outbox`. The scheduled relay claims due
  rows with `FOR UPDATE SKIP LOCKED` (safe across multiple app instances), dispatches them, and on
  failure records the error and reschedules with a fixed backoff curve (1m → 5m → 15m → 1h → 4h),
  marking `FAILED` after `relay.max-attempts`. Idempotency keys make retried application logic safe.
- **Suppression list.** Recipients on `MailSuppressionList` (hard bounces, complaints, unsubscribes) are
  dropped before every dispatch; a message left with no recipients is skipped. Feed it via the inbound
  webhook (`onec.mail.webhook.enabled=true`) — translate your provider's events to the neutral shape
  `[{"email","type":"bounce|complaint|unsubscribe","detail"}]` — or programmatically.

## Gotchas

- **SMTP settings are Spring's, not ours.** Host, port, username, password, auth and STARTTLS/TLS live
  under `spring.mail.*` (e.g. `spring.mail.properties.mail.smtp.starttls.enable: true`). `onec.mail.*`
  does not configure the transport.
- **From address is mandatory for SMTP.** If a message has no `from` and `onec.mail.default-from` is
  unset, the send throws `MailDeliveryException` ("No 'from' address ...").
- **`queue(...)` needs the outbox.** Calling it without a `DataSource` throws `IllegalStateException`.
  Either configure a `DataSource` or set `onec.mail.use-outbox=false` to dispatch synchronously.
- **Queued mail needs the relay running.** With `onec.mail.relay.enabled=false` (or no scheduling),
  queued messages persist but are never sent.
- **`http` provider needs `spring-web`.** `spring-web` is `compileOnly`; the `RestClient` dispatcher and
  the preview/webhook controllers only wire up when it's on the classpath. The web endpoints are also
  servlet-only and each gated by its own flag.
- **Preview is dev-only.** It instantiates a sample target via its no-arg constructor and renders
  templates over HTTP — never enable `onec.mail.preview.enabled` in production.
- **Delivery failures surface as `MailDeliveryException`** (a runtime exception) from all dispatchers.
