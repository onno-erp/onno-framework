# CRM channels starter

Add real messaging to the onno CRM without copying an example's Java classes:

```kotlin
implementation("su.onno:onno-crm-channels-starter:<onno-version>")
implementation("su.onno:onno-auth-starter:<onno-version>")
```

The starter includes `onno-crm-starter` transitively: contact identities, contact merge tools,
conversations, delivery status, the inbox widget, and the connection cards. It installs through
Spring Boot auto-configuration; **no component scan of `su.onno` is necessary**. All providers are
disabled by default. Gmail supports multiple shared and personal mailboxes per application. Telegram supports one bot with a shared ordinary-chat inbox and multiple personal/shared business connections. Instagram and
WhatsApp currently support one shared account each. Run one worker instance per deployment; use
separate deployments for separate tenants. No provider credentials or example sample data ship in it.

## Privacy: contacts and shared Telegram business accounts

> **Correspondents become shared contacts.** People who write to a connected account — including a
> **personal** Gmail mailbox or a **personal** Telegram business account — are resolved into the
> host's shared contact catalog through `CrmCustomerBinding`. The *conversations* of a personal
> account stay private to its owner, and its channel identities are hidden from other users, but the
> contact records themselves are ordinary host catalog records: who can see them is decided by the
> host's contact permissions, not by this starter. Tell users this before they connect a personal
> mailbox, and restrict contact access in the host if that is not acceptable.

> **Warning: a SHARED Telegram business account exposes the verifying user's own chats to the team.**
> Telegram business routes the private chats of the Telegram user who presses Start. On a *shared*
> account those chats — with friends and family as well as customers — land in a team inbox. In
> Telegram's Chat Automation / Business chatbot settings, limit the bot to **selected chats** (or
> exclude personal contacts) rather than all chats, and prefer a dedicated business Telegram account
> for shared connections.

## Provider setup

| Provider | Enable property | Private credentials | Delivery |
| --- | --- | --- | --- |
| Telegram | `onno.crm.channels.telegram.enabled` | `onno.crm.channels.telegram.token` in external config | Ordinary bot chats (optional, `regular-chats-enabled`) and scoped business connections, long polling, replies, incoming and outgoing files |
| Gmail | `onno.crm.channels.gmail.enabled` | `client-file`, `tokens-file` under the Gmail prefix | Google OAuth, recent inbox/history polling, incoming attachments, threaded replies with files, import of replies sent from Gmail on known threads |
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
Emails are read whole, up to Gmail's 50 MB maximum, through a private copy of the JSON mapper (the
application's shared mapper keeps Jackson's default string limit). An email that keeps failing for its
own reasons, such as a response that cannot be read or MIME that cannot be parsed, is logged with its
Gmail id and skipped after three sync rounds, so it cannot hold up the rest of its mailbox; network,
access and rate-limit failures never count against an email. When Gmail rate-limits a mailbox, polling
leaves that mailbox alone until the time Gmail gives, because asking earlier only extends the limit.
Failures are logged with the mailbox, the HTTP status and Google's error reason.

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
require explicit retry; automatic retries must not create duplicate customer messages. A failure
before anything was sent (for example a rate-limited Telegram business permission check) leaves the
reply queued and retries after a backoff that honours the provider's `retry_after`. Instagram
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

## Host catalog binding

The channel starter activates only when the host registers `CrmCustomerBinding`. Every adapter uses
`CrmContactService.resolveIncoming` to resolve a scoped channel identity; unknown peers invoke the
binding's explicit inbound-contact callback. No adapter creates or updates a CRM customer catalog.
The host owns customer creation/validation, record permissions, employee identity and page routes.
Telegram avatars remain identity-owned. Channel management and Gmail OAuth use `CrmChannelAccess`
(ADMIN by default); cached Telegram avatars require host customer and inbox workspace access.
See [the composition guide](../onno-crm-starter/README.md). No compatibility layer for the old CRM
customer/agent model is provided.

Gmail authorization accepts `{returnPath:"/your/settings/page"}` and returns to that local page after
the callback. The settings widget supplies its current path; the host need not define `/crm-settings`.

CRM channels use extensible string keys with connector-owned `CrmChannelDefinition` metadata
(`GET /api/crm/channels/types`); no fixed channel enumeration or Channels page is installed.
The optional settings widget uses `crm.channel.settings` extension contributions; bundled provider
setup and branding live in the channels starter. Workspace `.list(...)`/`.view(...)` uses ordinary
`ListSpec` resolution for both the inbox renderer and table. Status bindings read an existing host
catalog or enumeration through `CrmStateConfiguration.catalog(...)`/`.enumeration(...)`, with no
CRM status table or mirrored records. Channel keys and status UUIDs are breaking storage changes.

## Gmail account ownership and lifecycle

Enable Gmail and supply one private Google Web OAuth client registration for the deployment.
Connections can then create multiple **shared** accounts (administrators) or **personal** accounts
(the current user with inbox write access). Each mailbox is bound to a separate inbox and UUID.
The same mailbox cannot be attached to a second shared/personal account. Reauthorization must use
the original mailbox, preserving routing and history.

`POST /api/crm/gmail/authorize` accepts `{returnPath, accountId?}`. When `accountId` is present, the
server rechecks ownership and workspace write permission before authorization and again at callback.
The one-use session attempt binds the principal, account UUID, return path, expiry, state and PKCE
verifier. Omitting `accountId` retains the legacy ADMIN-managed deployment account.

Credentials live in owner-only `gmail-<account UUID>.json` files beside the configured `tokens-file`.
The original `tokens-file` remains the legacy mailbox's credential file. Per-account clients isolate
refreshes, synchronization, sending and health state. Pause affects only that inbox. Disconnect stops
sync/sends, removes its local credentials and fails queued replies; conversation history and ownership
remain. It does not revoke the Google grant; users can revoke that separately in Google. Reconnect
requires consent again. No real authorization or outbound messages are exercised by automated tests.

The UI advertises disabled providers, but server configuration still enables adapters. For Gmail,
the OAuth client must be present before Add account becomes available. Other bundled providers
explicitly advertise shared-only setup. See the CRM starter's scoped account contract for host widget
configuration and provider extension points.

## Telegram business connections

Enable business mode for the bot in BotFather and configure its token privately. The server remains
one polling owner for that bot. Keep webhooks disabled for this polling deployment. Ordinary bot
chats remain the existing shared Telegram inbox; **Telegram business** is a separate managed provider
(`telegram-business`) supporting both Personal and Shared accounts. CRM ownership controls visibility;
Telegram mode controls the sender identity. No automated replies are generated by this integration.

In Connections, select Personal or Shared, add Telegram business, and choose Connect. The server
returns an expiring `t.me` link. Open it in Telegram and press Start to prove your Telegram user.
Then select the bot in Telegram's Chat Automation / Business chatbot settings, choose allowed chats,
and grant reply permission. The one-use proof is bound to the CRM account and principal; only a
matching provider-verified Telegram business owner can attach. Reauthorization must prove the same
Telegram user. A bot connected to multiple Telegram users routes each to a separate account/inbox.

Business events, message edits/deletions, routing and queues use additive `onno_crm_tg_business_*`
tables. Unclaimed connections retain only connection metadata; their messages are not imported.
Incoming private customer messages are deduplicated per connection/chat/message. Replies sent directly by the connected Telegram owner are imported into existing chats as sent
agent replies. Connected-bot echoes are ignored. External replies clear unread messages up to
the reply time, preserving later incoming messages and CRM read state. Incoming attachments are stored locally with previews/downloads (up to 20 MB per file); replies can send supported uploads. Each outgoing text/file
includes `business_connection_id`, checks current reply permission and the 24-hour reply window,
and refuses changed destinations. Uncertain deliveries fail and need explicit review/retry.

Pause stops imports and sends for that account. Disconnect preserves history/ownership and stops
queued sends; remove the bot in Telegram to revoke its provider access too. Reconnect uses the
original Telegram owner. Regular bot chats and business chats never share routing or identity scopes.
`onno.crm.channels.telegram.regular-chats-enabled=false` makes the bot business-only: ordinary
(non-business) bot chats are ignored, no ordinary inbox is created, the **Telegram bot** provider is
shown as unavailable, and replies to previously imported ordinary chats are not sent. An ordinary
inbox created earlier keeps its history.

**Behaviour change:** while the ordinary Telegram inbox is *paused*, ordinary messages that arrive
are **discarded**, not queued — polling continues so business chats and onboarding keep working, and
the cursor moves past those messages. The same applies to a paused business account's chats.
No historical Telegram conversation import is provided.

Telegram intake handles each update on its own. File downloads and business-connection lookups run
before the database transaction and CRM workspace lock. A file that cannot be downloaded or stored is
recorded as a placeholder line (for example `[File could not be imported]`) on the message. An update
that still fails is retried up to three times on later polls and then skipped (logged with its update
id), so one bad update cannot stall every other chat.

`POST /api/crm/telegram/business/{account UUID}/authorize` requires account management rights and
workspace write access, uses the normal CSRF boundary, and returns `{url}`. No bot credential is
exposed. The existing account API provides pause/resume/check/disconnect and scope selection at
creation. Changing an account's visibility after it has history is not supported; choose its scope
before connecting.

Gmail incremental sync imports SENT replies to known CRM threads, deduplicating CRM sends.
External replies clear earlier CRM unread messages; newer incoming messages remain unread.
This does not synchronize provider read labels or import unrelated sent-mail conversations.

Gmail imports MIME attachments and inline image parts (up to ten files, 20 MB each), decoding filenames.
On upgrade it restores attachments on already-imported messages among the latest 50 inbox emails of
mailboxes connected before attachment import existed, without changing unread state. Mailboxes
connected afterwards import files from their first sync and are never backfilled. Telegram ordinary/business messages import photos, documents, video,
audio, voice notes and stickers. Oversized/unavailable Telegram files retain an explanatory placeholder. Files the deployment's media
policy (`onno.media.*` size and content-type limits) refuses are skipped on both providers with a short
placeholder note on the message; the rest of the mail or message still imports.
Telegram cannot recover files from old bot updates whose payloads were not retained.

Gmail also keeps an email's HTML part. It is sanitized on import with an allowlist (layout tables,
inline styles and images stay; scripts, event handlers, forms, frames and non-http(s)/mailto/cid
URLs are removed), capped at 500,000 characters, and stored in `ConversationMessage.bodyHtml`
alongside the plain-text `body`. Inline `cid:` images point at their stored attachment copies.
HTML-only mail gets a readable plain-text body. Once per mailbox, the latest 50 inbox emails that
were imported before HTML was kept receive their HTML without re-downloading files. Outgoing
replies stay plain text.

## Known limitations

- A disconnected Gmail mailbox or Telegram business user stays bound to the account it was first
  connected to. It cannot be attached to a different account; reconnect the original account instead.
- One worker instance per deployment polls each provider; there is no leader election.
- Read state is not synchronized back to providers.
- `TelegramClient.Update.payload()` exposes Telegram's raw update JSON for adapters inside this
  starter; it is not a stable API and its shape follows the Telegram Bot API.
