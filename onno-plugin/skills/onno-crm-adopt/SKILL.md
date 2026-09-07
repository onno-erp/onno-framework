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
no workspace displays. The module owns customers, identities, conversations, messages and the UI.
Use authored Java metadata/customizers for fields, folders and actions; do not fork the inbox widget
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
