---
name: onno-crm-adopt
description: Install and configure the packaged onno CRM and channel starter in an adopter application, including team workspaces, contact identities, and connection cards.
---

# Adopt the packaged CRM

Use `su.onno:onno-crm-channels-starter:<same-onno-version>` for built-in Telegram, Gmail,
Instagram and WhatsApp adapters, or `onno-crm-starter` alone for the business module. Use the
published artifact (Maven local for unreleased work), not `includeBuild` or copied example classes.
Spring Boot auto-configuration discovers it; a broad `su.onno` component scan is unnecessary.

The host owns authentication, team access and `CrmInboxWorkspace` predicates. Explicitly include
wanted `Channel` values in each workspace; a working connector can otherwise import messages that
no workspace displays. Register `CrmCustomerBinding` against the host catalog to enable the module. It owns channel identities, conversations, messages and widgets; the host owns customers, employees, business fields, pages and navigation.
Use ordinary host catalog metadata for fields, and CRM presentation customizers for folders and actions; do not fork the inbox widget
for ordinary configuration. Preserve existing shared Button/Checkbox/Select components.

Before changing an existing adopter, inspect its dependency version, auth and workspace definitions.
Keep the database and private token files when replacing the old example adapters. Rename old
`crm.<provider>.*` keys to `onno.crm.channels.<provider>.*`. All providers default disabled.

Read the [channel guide](../../../onno-crm-channels-starter/README.md) in a source checkout for exact
configuration and current limitations. When installed without repository docs, inspect the artifact's
Spring configuration metadata and the matching-version module README. Do not infer features from
newer source code. For provider authorization, use `onno-crm-channel-setup`; for runtime failures,
use `onno-crm-channel-debug` when those skills are available.

Validate a plain external consumer can start, discover CRM metadata, render connection cards, and
leave disabled providers inactive. Keep sample accounts/data in the example only. Publishing to
Maven local is verification; public release remains the repository's tagged CI workflow.

Assignment requires optional `CrmAgentBinding`. Inbound new-contact resolution requires an explicit
host callback; adapters cannot invent customers. Personal groups require `CrmFeatures(true)`;
conversation statuses are optional `CrmStateConfiguration.catalog(...)` or `CrmStateConfiguration.enumeration(...)`. Customer
stages and sales pipelines are ordinary host code. CRM has no contact CRUD or merge/undo HTTP API.
Use host actions for consolidation, calling `CrmContactService.transferLinks` inside the host transaction
for CRM-owned references. Workspace roles are host-defined; do not require CRM_AGENT/CRM_MANAGER.

CRM channels use extensible string keys with connector-owned `CrmChannelDefinition` metadata
(`GET /api/crm/channels/types`); no fixed channel enumeration or Channels page is installed.
The optional settings widget uses `crm.channel.settings` extension contributions; bundled provider
setup and branding live in the channels starter. Workspace `.list(...)`/`.view(...)` uses ordinary
`ListSpec` resolution for both the inbox renderer and table. Status bindings read an existing host
catalog or enumeration through `CrmStateConfiguration.catalog(...)`/`.enumeration(...)`, with no
CRM status table or mirrored records. Channel keys and status UUIDs are breaking storage changes.

Priority is optional: bind a host catalog/enum with `CrmPriorityBinding.catalog(...)` or
`.enumeration(...)`; use `.options()` in an ordinary priority filter. No priority enum, default or
implicit column/filter is installed. The priority UUID is scoped to that host source.
Assignment, close/reopen, status-change and manual identity-linking HTTP actions are not bundled.
Host `EntityView<Conversation>` beans declare ordinary `ActionSpec` ROW/DETAIL handlers; extension
buttons receive their descriptors and call `context.execute(key, inputs)`. The scoped action API is
`/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions` (GET descriptors, POST `/{key}`
with `{inputs:{...}}`). It checks workspace write access, application read-only mode, action roles
and record visibility/enabled rules. CRM adds no business-field mutations or automatic history
messages for these actions. Hosts own the handler and any desired history records. Connector code
can still use the low-level identity-link service for provider routing.
