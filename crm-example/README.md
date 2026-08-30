# Onno CRM development consumer

A thin Spring Boot development app for the reusable [`onno-crm-starter`](../onno-crm-starter).
The published module owns the business model, APIs, views, and widget; this app owns only H2 sample
data, demo authentication, and shell branding. It demonstrates:

- unified Telegram/email/web-chat inbox;
- customer lifecycle, ownership, tags, and contact details;
- assignment, priority, open/waiting/snoozed/closed states;
- inbound messages, agent replies, and internal notes in one timeline;
- comments/mentions on customers, conversations, and opportunities;
- opportunity value, probability, expected close, next step, grouping, and KPIs;
- live refresh through the framework's entity-change event stream.
- a viewport-filling fixed workspace whose conversation list, timeline, and customer rail scroll
  independently;
- one chronological activity stream for source messages, Onno comments, and CRM lifecycle events,
  with customer/agent avatars resolved from the same Glass/avatar-field path as the Onno shell.

Run with `./gradlew :crm-example:bootRun` and open <http://127.0.0.1:8080>. The login screen has demo
accounts, and local development auto-signs in as Alice.

For continuous development, keep this running beside it:

```bash
./gradlew -t :onno-crm-starter:classes :crm-example:classes
```

The seeded channels simulate delivery. Production Telegram, WhatsApp, email, and telephony support
belongs in connector starters: receive provider webhooks, map external identities to `Customer` and
`Conversation`, append `ConversationMessage` records idempotently, and deliver queued outbound
messages with retry/audit handling.
