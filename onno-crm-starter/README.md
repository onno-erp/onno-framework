# onno-crm-starter

A reusable, Apache-2.0 CRM business module for Onno applications. Adding the dependency installs:

- customer and CRM-agent catalogs;
- channel inboxes and a unified conversation catalog;
- durable messages, Onno comments, and lifecycle/system events in one timeline;
- opportunity pipeline data and KPI pages;
- role-aware entity views and additive CRM navigation;
- the packaged `crmInbox` React renderer and `/api/crm/conversations/**` command API.

The host application retains ownership of its shell brand, theme, authentication mode, and identity
catalog. The module contributes navigation sections without replacing those choices, so the same
artifact can be used from ordinary Onno applications and from `onno-enterprise` verticals.

## Install

```kotlin
dependencies {
    implementation("su.onno:onno-crm-starter:$onnoVersion")
    implementation("su.onno:onno-auth-starter:$onnoVersion")
    runtimeOnly("org.postgresql:postgresql") // or H2 for development
}
```

No `@ComponentScan`, `@EntityScan`, widget plugin, or manual auto-configuration import is required.
The starter registers `su.onno.crm` as an Onno scan package, exposes its repositories, and serves
its compiled widget bundle from the classpath.

Users need `CRM_AGENT` or `CRM_MANAGER`; configuration writes require `CRM_MANAGER`. `ADMIN`
retains Onno's normal superuser behavior.

If the host uses `Agent` as its identity directory, configure:

```java
layout.identity(Agent.class, "email");
```

Otherwise the default `CrmAgentIdentityResolver` maps the authenticated username/email to
`Agent.email`. Enterprise applications with a different identity model may provide their own
`CrmAgentIdentityResolver` bean.

## Integration boundary

The module owns the CRM business model. Provider-specific Telegram, WhatsApp, email, social, and
telephony clients belong in separate connector starters. Connectors should resolve external
contacts to `Customer`/`Conversation`, append idempotent `ConversationMessage` records, and deliver
outbound messages through an audited retry/outbox boundary.

See [`crm-example`](../crm-example) for a thin development consumer with H2, demo users, sample
data, and shell branding.
