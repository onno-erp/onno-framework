# onno 3.0.0

CRM is now an opt-in messaging module around the consuming application's business model.
This is a breaking release; no automatic migration from the 2.5 CRM model is included.

## Host-owned business model

- Bind an existing customer catalog with `CrmCustomerBinding`; without a binding, CRM is inactive.
- Bind existing employee identities only when needed. CRM no longer supplies customer or agent catalogs.
- Status and priority choices come from optional host catalogs or enumerations. There are no built-in priority choices or default priority, and no mirrored CRM status catalog.
- Sales opportunities and metrics are an ordinary example recipe, compiled only with `-PsalesExample`.
- Host pages, navigation and `ListSpec`/`EntityView` definitions control inbox columns, filters, sorting, search, selection and renderer.

## Extensions and messaging

Channels use extensible string keys with connector-owned metadata, branding and setup contributions.
There is no automatically installed Channels page or fixed provider filter list. The bundled
connector starter owns its setup widget; other connectors use `crm.channel.settings`.

Assignment, close/reopen, status-change and manual identity-linking HTTP business actions have
been removed. Hosts author ordinary `ActionSpec` handlers and custom buttons. Scoped action
execution enforces workspace permissions, action roles, read-only mode and record conditions.
Hosts decide which fields to change and whether to record a history message.

Connectivity and reply capability are separate. A connected channel can be read-only or have a
closed reply window. The composer explains the restriction, preserves drafts and keeps internal
notes independent of external delivery. Sending and retrying enforce the same restrictions on
the server. The sender picker remains compact.

## Dependency security

MapLibre GL is updated to 6.8 and Vitest to 4.1.11 to resolve the release audit findings.
Map consumers use the new named-export API.

## Upgrade requirements

1. Read the [CRM integration contract](../onno-crm-starter/README.md) and supply explicit host bindings and UI definitions.
2. Plan and perform a data migration before upgrading an existing 2.5 CRM database. Customer and assignee references now use host-scoped UUIDs; channels are strings; status and priority UUIDs refer to host choices. Removed CRM business catalogs are not migrated automatically.
3. Replace removed contact CRUD/merge/undo APIs and business-action endpoints with host-owned workflows. The low-level CRM link-transfer hook does not merge host records or provide business merge auditing/undo.
4. Recompile custom widgets and connectors against the new APIs. Provider adapters report applicable channels and delivery capabilities explicitly.

A conversation still requires one record from the application's bound customer catalog. Zero or
multiple participants, and multiple participant catalog types, are not implemented in this release.

## Verification

Local verification includes full Gradle checks, frontend tests, Maven-local publication,
aggregate Javadocs, browser/API smoke tests and a standalone consumer using its own catalog,
custom channel key and status enum. CI repeats build and publication checks before publishing.
