# CRM channels starter

Add real messaging to the onno CRM without copying an example's Java classes:

```kotlin
implementation("su.onno:onno-crm-channels-starter:<onno-version>")
implementation("su.onno:onno-auth-starter:<onno-version>")
```

The starter includes `onno-crm-starter` transitively: contact identities, contact merge tools,
conversations, delivery status, the inbox widget, and the connection cards. It installs through
Spring Boot auto-configuration; **no component scan of `su.onno` is necessary**. All providers are
disabled by default. Each provider currently supports one account per application and one worker
instance; use separate deployments for separate tenants. This is an initial adapter release, not
multi-account hosted OAuth onboarding. No provider credentials or example sample data ship in it.

## Provider setup

| Provider | Enable property | Private credentials | Delivery |
| --- | --- | --- | --- |
| Telegram | `onno.crm.channels.telegram.enabled` | `onno.crm.channels.telegram.token` in external config | Private bot chats, long polling, text replies, avatars |
| Gmail | `onno.crm.channels.gmail.enabled` | `client-file`, `tokens-file` under the Gmail prefix | Google OAuth, recent inbox/history polling, threaded text replies |
| Instagram | `onno.crm.channels.instagram.enabled` | `tokens-file` under the Instagram prefix | Instagram Login professional account, provider-visible polling, text replies |
| WhatsApp | `onno.crm.channels.whatsapp.enabled` | `tokens-file` under the WhatsApp prefix | Cloud API, signed incoming webhooks, text replies, delivery/read receipts |

File defaults are `~/.config/onno/gmail-client.json`, `gmail-tokens.json`,
`instagram-tokens.json`, and `whatsapp-tokens.json`. Mount writable owner-only files where required.
Google's client file is its downloaded OAuth JSON. Its token file is created by the OAuth callback.
Instagram's file contains `{"access_token":"..."}`. WhatsApp's file contains:

```json
{
  "access_token": "...",
  "phone_number_id": "...",
  "business_account_id": "...",
  "app_secret": "...",
  "verify_token": "a-long-random-value"
}
```

Use environment variables, a secrets manager, or private external Spring configuration. Never
commit these values. Provider tokens are not returned through the connection-card API. Telegram
reconnect can persist into one existing external `.properties` file specified by
`spring.config.additional-location`; for other configurations rotate the secret externally.

### Gmail

Enable Gmail API in your Google project, configure the consent screen/test users, and create a Web
OAuth client. Register the exact `onno.crm.channels.gmail.redirect-uri` (default local example:
`http://127.0.0.1:8090/api/crm/gmail/callback`; configure your HTTPS deployment URL). In CRM settings,
a manager chooses **Connect Google account**. The flow uses state, PKCE, offline access and only
`gmail.readonly` + `gmail.send`. Scope verification and refresh-token persistence happen server-side.
It imports the latest 50 inbox messages initially, then follows Gmail history; an expired history
cursor triggers another bounded initial scan. A sender's Gmail threads share a CRM conversation,
while each reply retains the original Gmail thread and Message-ID references.

### Instagram

Create a Meta app with Instagram messaging, add a professional account/tester, accept the tester
invitation, and generate an Instagram Login token. Signing into Instagram alone does not grant
app permissions. Store the token privately and enable the adapter. The account is verified before
creating its inbox. The worker polls every 15 seconds, following provider cursors even on empty
pages; development-mode restrictions can return no visible conversations despite valid credentials.
Replace expired tokens externally, then choose **Check connection**. Instagram public webhook
hosting and app review are not automated by this version.

### WhatsApp and local testing

Create the WhatsApp use case in Meta, provision a test or production number, and store the token,
number ID, WABA ID, app secret, and a random verification token. Test numbers require verified
recipient numbers in Meta; a production number requires its own Meta setup. The dashboard's temporary
token expires; use a suitable long-lived system-user token for deployments.

Expose **only** `/api/crm/whatsapp/webhook` publicly. GET verifies `hub.verify_token` and returns
`hub.challenge`. POST checks HMAC-SHA256 against the exact raw request bytes using the app secret,
rejects bodies larger than 1 MiB, and filters events by configured WABA and phone-number ID. Duplicate
message IDs do not create duplicate CRM messages. Configure this URL in Meta and subscribe the app
to the WABA's `messages` events. A valid API token by itself does not prove webhook delivery works.

With `onno-auth-starter`, add this exact route to both public and CSRF-ignored paths. These properties
**replace** their defaults; preserve the existing paths:

```yaml
onno:
  auth:
    public-paths: [/error, /api/theme, /api/config, /api/branding, /api/auth/login, /api/auth/me, /api/auth/csrf, /api/divkit/login, /api/desktop/ready, /api/desktop/manifest, /api/crm/whatsapp/webhook]
    csrf-ignored-paths: [/api/auth/login, /api/crm/whatsapp/webhook]
```

For local testing, run the included route-limited proxy, then point a temporary tunnel at it:

```sh
python3 scripts/webhook_proxy.py --crm-port 8090 --port 8091
cloudflared tunnel --url http://127.0.0.1:8091
```

An alternative temporary tunnel is `ssh -R 80:127.0.0.1:8091 nokey@localhost.run`
(verify/accept its host key on first use). This forwards the same restricted proxy, not the CRM.

Use the resulting HTTPS hostname plus `/api/crm/whatsapp/webhook` as Meta's callback. The tunnel
terminates when the process stops, and a new quick tunnel gets a new hostname. Never point the
public tunnel directly at a demo CRM with demo sign-in. For deployment, use a stable HTTPS hostname
and a reverse proxy with the same route restriction. The Python helper is for development only.

## Customization and operations

`CrmChannelConnection` provides manager connection controls. `CrmMessageTransport` connects the
existing reply UI to durable outbound queues. Provider clients/transports can be replaced with host
beans of their concrete types; no duplicate fallback transport should be installed. Host-authored
`CrmInboxWorkspace` definitions control routing/access; include `Channel.INSTAGRAM` and
`Channel.WHATSAPP` in the relevant workspace predicate. The starter supplies no team-specific rules.

All external sends happen after database commit. Interrupted/uncertain sends become FAILED and
require explicit retry; automatic retries must not create duplicate customer messages. Instagram
and WhatsApp enforce the 24-hour customer reply window; template initiation and human-agent
extensions are not implemented. Unsupported incoming attachments are placeholders; outbound
attachments are not implemented. Pausing stops sends (and polling where applicable); signed
WhatsApp inbound events can still be stored. Database writes trigger the CRM's existing SSE updates.

Provider-owned `onno_crm_telegram_*`, `onno_crm_gmail_*`, `onno_crm_ig_*`, and `onno_crm_wa_*` tables
retain routing, cursors, deduplication and outbox state. Existing example data uses the same table
names. Migration from the earlier local adapters only changes configuration keys from `crm.<provider>`
to `onno.crm.channels.<provider>`; retain the database and private files. Gmail's earlier thread-per-chat
upgrade preserves messages and notes and soft-deletes superseded conversations.

## Skills and verification

The repository's `onno-plugin/skills` includes `onno-crm-adopt`, `onno-crm-channel-setup`, and
`onno-crm-channel-debug`. These cover installation and workspace configuration, provider setup,
and diagnosis without leaking credentials or sending unsolicited test messages.

Build verification: `:onno-crm-channels-starter:test`, `generateConfigDocs`,
`publishToMavenLocal`, then run a separate Maven/Gradle consumer with the published artifact.
Actual Maven Central releases remain tag-driven CI; adding this module does not publish it.

WhatsApp send failures log the CRM message ID, HTTP status and numeric Meta error code/subcode; credentials and provider response text are excluded. Check these diagnostics before retrying a failed reply.

For a Meta test-number allowlist formatting mismatch, the private WhatsApp JSON may include `"test_recipient_aliases": {"<inbound wa_id>": "<verified test destination>"}`. Only configure a destination verified to belong to the same person. This changes outbound addressing only; contact identities remain canonical. Omit this test workaround for normal production setup.

WhatsApp replies wake the sender immediately after the enclosing transaction commits (including explicit retries). Rollbacks never dispatch a reply. The 15-second worker scan remains a recovery fallback; a provider backoff or pause still takes precedence.
