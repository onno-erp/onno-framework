---
name: onno-crm-channel-debug
description: Diagnose onno CRM channel connection, missing chats, duplicate contacts, delivery failures, OAuth and webhook issues without exposing secrets.
---

# Diagnose CRM messaging

Start with the observed symptom and the running app/version. Inspect connection-card state through
`GET /api/crm/channels` as an authorized CRM user. Manager commands require CRM_MANAGER/ADMIN.
Check which process, port, database and external configuration actually run before restarting.
Preserve database files and credentials; stop the actual old listener before starting a replacement.

Missing chat: confirm the provider account/number, then its visibility/permissions, sync cursor,
deduplication state, and workspace channel predicate. Check the scoped workspace APIs; a 403 from
generic conversation lists can be intentional workspace isolation. Do not broaden permissions to
make a diagnostic call succeed. Distinguish provider polling delay from CRM SSE invalidation.

Duplicate chat/contact: provider IDs and connection scopes own identity, not display names. Gmail
can preserve several thread routes under one contact chat. Use the CRM merge preview/conflict
choices and audit/undo path; do not blindly merge by a shared name or rewrite provider routing IDs.
Check canonical contact resolution when messages arrive after a merge.

Failed replies: inspect durable QUEUED/SENDING/FAILED state, channel ownership, connection pause,
and Instagram/WhatsApp's 24-hour inbound reply window. A timeout can mean the provider accepted
it; do not automatically resend uncertain writes. Explicit retries must remain user-intended.
Delivery/read receipts should not regress a later status.

WhatsApp: verify raw-byte HMAC, exact WABA/number filters, callback challenge and messages
subscription. Unsigned requests must fail. Check that the reverse proxy exposes only the webhook
route and that auth permits that exact path. Temporary tunnels stop with their process and change
hostname on recreation. Instagram's polling adapter does not configure webhooks.

Never print bearer tokens, raw OAuth callback URLs, credential-bearing request URLs or message
bodies for routine diagnostics. Record status codes, counts, sanitized errors and correlation IDs.
Do not report success solely because a token verifies or a build exits zero: confirm the actual
running channel view and the specific behavior being investigated. Use deterministic fixture
messages in tests; send external messages only when explicitly authorized.
