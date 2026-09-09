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
  bot chats; a Telegram username is not a verified email/phone identity.
- **Gmail:** enable Gmail API; configure test users/consent; use a Web OAuth client with the exact
  `gmail.redirect-uri`. CRM managers use Connect Google account. The callback validates state,
  PKCE, identity and scopes, then stores refresh credentials. Initial sync is bounded, not a full
  mailbox archive. Existing Gmail threads from a sender share one CRM chat.
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
separately. State test-mode limits, token expiry and unsupported attachments/templates. The current
starter supports one account per provider/application and one worker instance, not tenant-wide
multi-account onboarding. Read the matching-version module README for complete setup contracts.
