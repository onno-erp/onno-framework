---
name: onno-crm-channel-setup
description: Connect Telegram, Gmail, Instagram or WhatsApp to the packaged onno CRM channels starter; configure credentials, OAuth and signed webhook setup.
---

# Set up CRM channels

Establish the provider/account and desired test or deployment environment from the request. Reuse
existing app registrations and credentials when appropriate. Never put credentials in source,
frontend state, screenshots in docs, or public configuration responses. Store provider tokens in
private server-owned files and use `onno.crm.channels.<provider>.*` configuration.

- **Telegram:** bot token, one polling owner, no conflicting webhook. The adapter handles private
  bot chats and scoped business connections; a Telegram username is not a verified email/phone identity.
  For business mode, enable it in BotFather, add a Personal/Shared Telegram business account, use
  its expiring Connect link and press Start, then connect the bot in Telegram Chat Automation /
  Business settings with reply permission. Do not equate business mode with personal CRM visibility.
- **Gmail:** enable Gmail API; configure test users/consent; use a Web OAuth client with the exact
  `gmail.redirect-uri`. CRM managers use Connect Google account. The callback validates state,
  PKCE, identity and scopes, then stores refresh credentials. It asks for read, send and modify;
  read and send are required, modify (mark read and move back to Inbox in Gmail) is optional, so a
  mailbox connected earlier must be reconnected to get it (`GET /api/crm/gmail/accounts` shows
  `canModify`). Initial sync is bounded (`initial-import-limit` 500, `initial-import-days` 90), not a
  full mailbox archive; older or archived mail is found and restored through the Gmail search/restore
  endpoints. Existing Gmail threads from a sender share one CRM chat.
- **Instagram:** professional account, app messaging permissions, accepted tester invitation, then
  Generate token and authorize the app. A normal Instagram login or accepted tester invite alone
  does not grant API access. Verify `/me`; a token can be valid while test-mode APIs expose no chats.
  If an embedded browser cannot open the authorization popup, use a browser that can, preserving
  the OAuth flow. Do not repeatedly tell the user to log in when the missing step is consent.
- **WhatsApp:** choose a test or authorized business number; store `access_token`, `phone_number_id`,
  `business_account_id`, `app_secret`, `verify_token` in the private token JSON. Configure the exact
  `/api/crm/whatsapp/webhook` HTTPS callback and subscribe the app to WABA messages. Confirm both
  challenge verification and signed delivery; a verified API token alone is not full connectivity.

For local WhatsApp testing, the module's `scripts/webhook_proxy.py` exposes only the signed webhook
route on loopback. Point a temporary tunnel at that proxy, not the full demo CRM. In cookie-auth
hosts, preserve all default public paths while adding the exact webhook route to public and
CSRF-ignored lists. Do not exempt `/api/crm/**`. Production needs a stable HTTPS callback.

Do account selection, provider verification and code configuration within the user's authorization.
Stop only for required personal verification or missing business information; do independent setup
work meanwhile. Do not bypass consent, identity verification or platform approval requirements.
Sending a real test message requires the user's authorized recipient/content; use mocks for
outbound verification otherwise.

Report account verification, inbound synchronization, outbound verification and webhook readiness
separately. State test-mode limits, token expiry and unsupported attachments/templates. Gmail supports multiple shared/personal mailboxes with isolated credentials and inbox routing.
Telegram also supports personal/shared business connections with one polling bot; ordinary bot chats, Instagram and WhatsApp remain shared-account adapters. One worker instance per
deployment is still required. Read the matching-version module README for complete setup contracts.
