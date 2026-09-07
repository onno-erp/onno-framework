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

Users need `CRM_AGENT` or `CRM_MANAGER`; channel connection commands require `CRM_MANAGER`. `ADMIN`
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

Applications supply their own sample data, authentication, and shell branding.


## Message delivery contract

Provide a `CrmMessageTransport` bean to connect messaging. Its `connection(Conversation)` returns
`{connected,label,maxTextLength}`. Its `enqueue(Conversation, ConversationMessage)` must durably
queue the message in the caller's database transaction, without network I/O. The default bean is
disconnected; sending on an unlinked channel fails with 422 instead of leaving a false queued reply.
A host adapter owns external contact ids, credentials, checkpoints, delivery claims, and retries.
`onno-crm-channels-starter` provides opt-in messaging adapters.

`GET /api/crm/conversations/{id}/delivery` exposes safe connection details to the composer.
`POST /api/crm/conversations/{id}/messages/{messageId}/retry` requeues only a failed outbound agent
reply belonging to that conversation; the transport must atomically reject an already claimed or
sent message. Both routes require CRM access, and retry requires the normal session CSRF token.
The UI disables unconnected replies and renders Queued/Sent/Failed honestly; the Retry control warns
that an interrupted attempt might already have sent. Internal notes remain ordinary Onno comments.

## Developer configuration and channel connections

**Configuration → Channels** (`/ui/crm-settings`) manages connector status and setup. It is not a
layout editor. Managers/admins can invoke connector-provided actions; agents can see status.
Telegram supports checking, pausing/resuming and reconnecting the existing bot in the sample host.
Instagram, WhatsApp, Gmail and Microsoft 365 show “Setup required” until their host connector is
installed. Listing a channel does not implement OAuth or enable message delivery.

Fields, sections, formats, custom-field definitions, list bindings and button visibility/order are
owned by Java. Register ordered `CrmWorkspaceCustomizer` beans in the host, for example:

```java
@Bean
CrmWorkspaceCustomizer crmLayout() {
    return defaults -> defaults.withFields(List.of(
        new CrmWorkspaceService.DisplayField(
            "customer.description", "Name", "Contact", "text", true, true),
        new CrmWorkspaceService.DisplayField(
            "custom.account_tier", "Account tier", "Relationship", "badge", true, true)
    )).withCustomFields(List.of(
        new CrmWorkspaceService.CustomField(
            "account_tier", "Account tier", "select", List.of("Standard", "Premium"), false)
    ));
}
```

`Config.withActions(...)` controls supported actions; the full `Config` record also defines list
bindings and photo/activity/timestamp/delivery display flags. Definitions are validated at startup;
changes take effect on restart. Customer custom values remain persisted independently. Preserve
custom-field keys/types/options when evolving code so existing values remain readable. Legacy
`onno_crm_workspace` data is left untouched but is no longer read or writable through the API.
The contact editor uses the host Onno Select, Checkbox and Input controls. Branded channels use
bundled provider artwork; unbranded email, phone and web chat retain neutral Onno symbols.

Custom fields support text, number, date, boolean, select, email, phone and URL values (50 definitions,
100 displayed fields maximum). Backend validation and access control remain independent of layout
visibility. Shell navigation and the host list toolbar remain authored through Onno UI metadata.

The contact panel and saved customer detail now offer editing, identity linking, merge preview,
explicit conflict choices, merge history and conservative undo. A customer can own several
`CrmContactIdentities`, scoped by channel, connection and external ID. Manual email/phone addresses
are unverified; provider connectors call `CrmContactService.link(...)` with their connection scope.
Identity collisions are rejected. Same-email/phone/name suggestions require human review; a display
name never triggers an automatic merge.

Merging moves active conversations, opportunities, identities and customer comments to the chosen
surviving customer. Conversation/message IDs and provider reply destinations are unchanged. Missing
values are filled, conflicting built-in/custom values require an explicit source/target choice, and
the duplicate is soft-deleted with a canonical redirect. Both preview and edit commands carry a
revision fingerprint to reject stale writes. Merge and undo are transactional and audited with actor
and timestamp. Undo restores moved relationships and customer values only when the affected records
have not changed since the merge; otherwise it refuses to overwrite later work.

Infrastructure tables owned by this starter are `onno_crm_contact_values`,
`onno_crm_contact_guard`, `onno_crm_customer_redirect` and `onno_crm_merge`; the identity catalog uses
ordinary framework metadata/schema/repositories. The contact guard serializes CRM merge, custom
value writes and connector identity linking. Generic repository writes retain optimistic
version checks. This starter's merge transfers the built-in CRM relationships only: host applications
with additional customer references must extend their consolidation policy before using it for those
references. Customer custom values are available through `/api/crm/contacts/{id}`, not injected into
the generic catalog row contract.

Commands (session auth and CSRF apply):

- `GET /api/crm/workspace` returns `{workspace:{version,config,availableFields},canConfigure}`;
  this endpoint is read-only (`canConfigure` is always false). Layout POST is removed.
- `GET /api/crm/channels` returns `{channels,canManage}`; manager/admin-only
  `POST /api/crm/channels/{key}` accepts `{action,credential?}` for an installed provider.
  Credentials are never included in its response.
- `GET /api/crm/contacts?q=...` searches up to 50 active customers;
  `GET /api/crm/contacts/{id}` returns fields, custom values, revision, identities, conversations and duplicate suggestions.
- `POST /api/crm/contacts/{id}` accepts `{revision,fields,customValues}`.
- `POST /api/crm/contacts/{id}/identities` accepts `{channel:"EMAIL"|"PHONE",address}`.
- `POST /api/crm/contacts/merge-preview` accepts `{source,target}`;
  `/merge` accepts `{source,target,revision,choices}` where conflict choices are `source` or `target`.
- `GET /api/crm/contacts/{id}/merges` lists audit records;
  `POST /api/crm/merges/{id}/undo` attempts a safe reversal.

CRM customer, opportunity and conversation **table views** enable `ListSpec.selectionCheckboxes(true)`.
The optional column uses Onno checkboxes and the common **Actions for selected** menu. Grouped
opportunities select loaded expanded rows; the custom inbox conversation renderer is unchanged.

The inbox uses quiet conversation rows with inset separators, a rounded selected background,
a single bordered composer surface, and compact channel badges. Its Onno channel picker
selects an existing conversation for the same contact from the current list view; it does not
create a new provider destination. Clear list filters to include other conversations. The picker
is locked while a draft is present or an internal note is selected. The Reply / Internal note
switch sits below the composer; notes keep their team-only styling and destination.

Conversation folders are opt-in developer configuration. `withFolders(List.of())` keeps the flat
list. Folder order and labels are authored by the application; channels, statuses and priorities
use the CRM enums. Empty criteria accept any value; populated criteria intersect, and values
within each criterion are alternatives. Unread folders require a positive unread count. Folders group chats alongside ungrouped conversations. The first matching folder in configuration
order owns a chat's position, so it appears only once. Folder position follows its first member in
the list; empty folders remain visible at the end. Counts and contents respect the host list's
current filters and loaded rows. Opening a folder slides its chats in from the right; Back to
conversations reverses the slide, with reduced-motion support and keyboard focus restoration. Opening or closing a folder preserves the active chat and draft. Detail content stays mounted
during the transform-only slide; keyboard focus moves without scrolling the list container.

```java
@Bean
CrmWorkspaceCustomizer folders() {
    return config -> config.withFolders(List.of(
        Folder.channel("telegram", "Telegram", Channel.TELEGRAM),
        new Folder("urgent", "Urgent", List.of(), List.of(),
            List.of(ConversationPriority.HIGH), false),
        new Folder("unread", "Unread", List.of(), List.of(), List.of(), true)));
}
```

`Folder` is `CrmWorkspaceService.Folder`. Folder keys must be unique stable lowercase identifiers;
labels are required, and at most 30 folders are supported. Existing Config constructors remain
available and default to no folders.

To group specific chats, use `Folder.conversations("team", "Team", List.of(conversationId1,
conversationId2, conversationId3))`. These are conversation UUIDs, not customer IDs. It can be
combined with the full constructor's channel/status/priority criteria. No chat records are moved.

### Multiple messaging providers

Register one `CrmMessageTransport` bean per provider. `ConversationService` collects them and
uses `CrmMessageRouter` to enqueue through exactly one connected provider. Each provider must
claim only conversations mapped to its own account/inbox; channel alone is insufficient when
several accounts use the same channel. Zero connected providers rejects sending; overlapping
claims fail before enqueueing. Enqueue remains in the caller's database transaction.

### Inbox workspaces and channel accounts

The persisted `Inbox` catalog is a **channel account** (provider/channel + handle), and
`Conversation.inbox` remains the reply-routing reference. Application-authored `CrmInboxWorkspace`
beans are separate team views. Their typed Java predicate chooses chats from any combination of
accounts, channels, assignees or explicit conversation IDs. New chats automatically appear wherever
they match. Workspaces may overlap; selecting one never rewrites a provider destination.

```java
@Bean
CrmInboxWorkspace supportInbox() {
    return new CrmInboxWorkspace("support", "Support",
        Set.of("SUPPORT", "SUPPORT_AUDITOR"), Set.of("SUPPORT"),
        chat -> Set.of(Channel.EMAIL, Channel.TELEGRAM).contains(chat.getChannel()),
        config -> config.withFolders(List.of(
            Folder.channel("email", "Email conversations", Channel.EMAIL))));
}
```

Callers also need a base `CRM_AGENT` or `CRM_MANAGER` role. Read roles and write roles are separate;
an empty write-role set makes a workspace read-only. `ADMIN` remains the superuser. Workspace keys
are stable, unique identifiers. The configuration function can customize fields, actions, labels,
folder rules and presentation independently for each workspace. Selection predicates must be fast,
deterministic and side-effect-free. Keep sensitive team boundaries in the predicate and roles;
a folder is presentation, not authorization. Definition order chooses the default inbox on `/inbox`. To give another workspace its own page, use
`page.bare().widget("Support inbox").type("crmInboxWorkspaces").config("workspace", "support")`
in a `Page` bean, then add that route through `Layout`. The header provides search and status,
channel, and priority filters; it does not switch workspaces.

With no workspace beans, the existing unified list and APIs retain their behavior. With workspace
beans, `/ui/inbox` renders the scoped workspace widget with search and 100-row pages. Workspace
selection, row membership and write permission are rechecked on every conversation command. The
server currently evaluates Java predicates over active conversations; this is intended for modest
CRM datasets, not a SQL-backed selection engine for millions of chats.

Generic conversation/message reads and writes (including generic forms, exports and MCP CRUD) are
blocked for non-admins in workspace mode, avoiding an unrestricted alternative to the scoped API.
Use the dedicated workspace endpoints below; generic admin tooling remains available. Contacts
remain shared CRM master data under the existing catalog roles. The contact panel filters its
conversation collection by workspace visibility. Contact edits require access to a related writable
chat; merges require write access to every affected chat. Merge history/undo are administrator-only
in workspace mode. Opening generic conversation detail forms is omitted from the scoped widget.

- `GET /api/crm/inbox-workspaces`: permitted workspace keys, labels and `canWrite`.
- `GET /api/crm/inbox-workspaces/{key}?q=...&offset=0&status=all&channel=all&priority=all`: authorized rows, `config`, `total`,
  `hasMore`, `canWrite`; a page defaults to 100 rows (up to 500 with `limit`). Negative offsets return 400.
- Existing `/api/crm/conversations/{id}/...` reads/commands require `?workspace={key}` when
  workspace mode is enabled. Missing scope, foreign membership or insufficient roles return 403.
- `GET|POST /api/crm/inbox-workspaces/{key}/conversations/{id}/comments`: authorized internal
  notes in the existing Onno comment store. POST accepts `{ "body": "..." }` (1–8000 characters).

Workspace feeds subscribe to content-free `updated` SSE invalidations with `entityType: "page"`,
`entityName: "crm-inbox-workspaces"`, and null record ID. Original conversation/message/comment
SSE events are suppressed for non-admin workspace users. Refreshes always go back through scoped
reads. Notes written through other trusted application services also invalidate the widget.

Inbox status, channel, and priority filters accept comma-separated enum names (OR within a field,
AND between fields); omitted values default to `all`. The inbox reuses the host `OptionsFacet`
multi-select chips through the widget SDK.

The scoped inbox renders the standard `EntityListWidget` header and body-view controls. Its feed
also accepts host list parameters: repeated `in=field,value`, `sort`, `dir`, `limit` (1–500, default
100), and an offset cursor returned as `nextCursor`. Membership is checked before filtering, sorting
and pagination. This cursor is an offset, not a stable snapshot under concurrent inserts.

To merge contacts from the customer list, select exactly two checkboxes and choose **Merge contacts**.
Choose the surviving contact, review relationship counts and conflicting fields, then confirm.
The operation uses the same revision-checked, authorized merge and undo service as the contact panel.

### Custom CRM chat message bodies

Apps can register React message components from `src/main/widgets/*.tsx` with
`registerChatMessageRenderer` in `@onno/widget-sdk` (UI host v4+). CRM retains the
avatar, author, timestamp, delivery status, and retry controls. Internal notes and
system events retain their existing renderers. This is a display extension; it does
not add rich-message transport or a new stored payload format.

```tsx
import { registerChatMessageRenderer, type ChatMessageRendererProps } from "@onno/widget-sdk";

function TelegramBody({ message }: ChatMessageRendererProps) {
  return <div className="whitespace-pre-wrap text-sm">{message.body}</div>;
}

registerChatMessageRenderer({
  id: "myapp.telegramBody",
  priority: 10,
  matches: message => message.channel === "TELEGRAM",
  component: TelegramBody,
});
```

The component receives read-only `message` data and a `fallback` React node. It may
use SDK hooks/primitives and load application data using the message ID. Higher
priority matches win; ties sort by ID. Registration replaces the same ID and returns
an unregister callback. Late registrations update visible messages. Missing matches,
failed predicates, and render errors preserve text display (failed predicates are
skipped). Never render untrusted message bodies as raw HTML. Interactive components
must call authenticated application commands for changes; registration grants no
additional backend permissions.

Inbox badges use the Gmail artwork for email conversations in connector-created `Gmail · <account>` inboxes, including message avatars and the reply-channel selector. Other email inboxes retain the neutral mail icon.

The inbox acknowledges loaded messages while the selected conversation is visible, including live arrivals and returning from a hidden browser/workspace tab. Failed acknowledgements keep the unread state and retry on a subsequent live update or focus/visibility change.

The example app owns reply templates as a `CrmReplyTemplates` catalog (name, category, plain-text body,
active flag). CRM managers maintain them in Configuration → Reply templates; agents can read
and insert active templates from the chat composer. The searchable picker previews the text,
appends it to an existing draft, and rejects insertion beyond the channel text limit without
truncation. Agents review/edit the draft and send explicitly. These are saved replies, not
provider-approved WhatsApp message templates. Inactive or deleted templates are excluded.

Conversation rows support right-click or Shift+F10 for opening the conversation and editing contact tags. Tags display as badges on the contact card when that field is visible. Edits use the existing authenticated contact command and revision check, preserve unrelated/custom fields, and apply across the contact’s channels.

### Unified contact activity

The CRM Inbox presents one row per contact and a chronological timeline across its channel conversations, internal comments and system events. Underlying conversations retain provider routing and email thread identifiers. The reply selector changes only the destination, and defaults to the latest incoming message's conversation; an unsent draft keeps its selected destination.

`GET /api/crm/contacts/{customer}/activity?offset=0&limit=100` returns `{entries,total,hasMore}` (newest first, limit 1–500). Each entry includes `id`, `conversationId`, `subject`, `channel`, `kind`, `direction`, `authorName`, `body`, `at`, and `deliveryStatus`. Only readable conversations are included, with canonical contact resolution after merging. The UI loads older pages on demand and updates through existing CRM SSE invalidations.

`POST /api/crm/contacts/{customer}/activity` accepts `conversationId`, `type` (`QUOTED`, `CALL_PLANNED`, `CALL_COMPLETED`, `MEETING_PLANNED`, `OTHER`), nonblank `details` up to 7000 characters, and optional local `scheduledFor`. It requires write access to the matching contact's conversation. It stores an internal `SYSTEM_EVENT` without sending a channel message or changing the sales stage. Planned dates are descriptive event text, not reminders or calendar invitations. Domain workflows can also append system events through the existing conversation-message repository.

Contact tags now use Onno's structured record tags: use Add tag on the contact card to select an
existing catalog tag. Remove individual chips with ×. The context menu
offers a Tags submenu with checked catalog entries. Legacy comma-separated tags are imported once; the legacy form field is
hidden. Templates are hidden when the active library is empty and reappear on template changes.

Manage tag definitions in Configuration → Tags (`CrmTags`), using normal catalog create/edit/delete
forms and the color field. Managers maintain the catalog; agents select its entries with Add tag.
The reply composer overlays the history viewport: messages scroll beneath its translucent surface,
with bottom space measured from the composer so the newest message can be scrolled fully above it.

Internal CRM notes keep the inbox composer and use a compact inline reference picker: `@` selects people, `#` selects catalog/document records, and pasted local record links become references. Notes remain ordinary Onno comments, including permission checks and mention notifications. The host `CommentBody` renderer accepts a stored `body` and resolves links for the current viewer. CRM reply drafts and internal-note drafts stay separate.

Only one root context menu stays open at a time. Right-clicking another conversation replaces it; the Tags flyout toggles assignments directly without a separate management panel.

Unified contact activity entries include `authorAvatarUrl` and `mine` for internal notes. Ownership matches the signed-in identity record ID, not the display name; photos resolve from the live identity catalog using the standard comment avatar resolver. The inbox preserves both fields when rendering notes.

Opening a unified chat selects the channel of its latest incoming or outgoing message, ignoring internal activity. While it remains open, only a newly received client message changes that selection automatically, and unsent drafts suppress automatic changes.

The inbox contact details panel starts hidden. Use the panel button in the chat header to show or hide it; on smaller screens it overlays the chat and includes a close button.

The inbox contact-details panel opens as an inset rounded island in its own animated grid column, reducing the chat width rather than covering messages. Its contents scroll independently; closed details are inert, closing restores focus to the toggle, and reduced-motion preferences disable the transition.

### Personal CRM chat groups

The inbox right-click menu offers **Move to group**, **New group…**, and **Remove from group**.
Groups organize unified contact chats and persist per authenticated username and inbox workspace,
without changing shared folder configuration or conversation access. Group headers offer rename
and delete; deleting a group keeps its chats. Personal groups take precedence over configured
folder rules, and an empty personal group matches no chats.

`GET /api/crm/chat-groups?workspace=<key>` returns `{key,label,customerIds}[]` for the current user.
`POST` to the same route accepts `{operation,key?,label?,conversationId?}` with operations
`create`, `move`, `rename`, or `delete`; create/move require an accessible conversation and move
with a null key removes membership. The server resolves its contact; clients cannot submit an
owner or arbitrary contact IDs. Names must be unique ignoring case within a workspace and contain
1–80 characters; each user can create up to 30 groups per workspace. Mutations return the updated
list and serialize read-modify-write operations transactionally. Existing CRM role and workspace
read access checks apply, and the ordinary CSRF protection covers writes. Grouping does not alter
contact data or send messages. The UI refreshes groups after changes and when the window regains focus.

### Inbox UI extensions

Host v5 / `@onno/widget-sdk` contributions can add chat header buttons, composer tools, context-menu
items, and right-panel content through named `crm.chat.*` outlets. The host supplies the selected
conversation, workspace, permissions, refresh/navigation callbacks, draft insertion, and the
existing authorized `logActivity` command. No provider-specific UI is imported by `CrmInbox`.
See [UI contributions](../docs/EXTENDING.md#ui-contributions-host-contract-v5).
Mock integrations belong in application widget bundles.

### Opening a workspace conversation

In workspace mode, `/api/divkit/catalogs/crm_conversations/{id}` (also the logical-name aliases)
is a CRM-owned conversation view. It verifies CRM role and record membership, then renders the
chat using an accessible inbox workspace. Its feed is
`GET /api/crm/inbox-workspaces/{key}/conversation/{id}`; it returns only that contact's conversations
within the authorized workspace and retains normal filtering/pagination. Foreign records return
403 and missing records return 404. Generic catalog/message read and export restrictions remain
unchanged. Without workspace definitions, the normal framework catalog view remains in use.

Applications own their status, Assign, Details, Close/Reopen, and Log activity controls
through application-authored widget contributions. The contact panel has no built-in edit, merge, or identity-linking forms.
The CRM starter supplies slots and authorized commands, without registering these buttons.
Application code may omit, replace, reorder, or add its own contributions.

In the inbox, Escape dismisses the selected chat, then closes an open folder to
return to the conversations list. With neither a chat nor a folder open, Escape
falls through to the normal tab shortcut. Menus and
popovers handle Escape first, and live updates do not reopen a dismissed chat.

An application may own a Templates catalog, picker, and button, registering `example.crm.composer.templates` in `crm.chat.composer`; the CRM starter
contains no template catalog or picker. Replace that contribution
ID to customize it, or register additional IDs to add neighboring buttons. The
composer supplies `insertDraft` only in reply mode; the host preserves the existing
draft, enforces the channel length limit, and never sends on insertion. Empty
template catalogs remain hidden.

Contact stages and conversation statuses are defined by an application `CrmStateConfiguration`
bean, including labels, colors, persistent UUIDs, closed semantics, and incoming/reply/close/reopen
transition targets.
The starter supplies no business choices by default. Code definitions are mirrored at startup into
read-only backing catalogs for existing references and color rendering; no catalog configuration
screens are added, and generic writes are denied, including for admins. Removed choices are
archived while historical references retain their display values. Keep IDs stable when relabeling.
Projection runs only with schema mode `apply`; plan/validate do not change this data.
`Customer.stage` and `Conversation.status` remain optional references; contacts/conversations may
start unassigned until application code or an incoming-message transition assigns a value.
The example's compatibility migrations preserve prior enum UUIDs. Legacy enum names remain
accepted in stored folder filters; new filters use configured UUIDs. Opportunity stages are a
separate model and unchanged here.

`Badge` accepts an optional configured hex `color`, using the shared `enumPillStyle` contrast calculation. CRM contact stages and entity tags use this filled pill treatment; unknown colors fall back to the semantic badge variant.

The Details header action opens the conversation URL through `context.openRecord`. Optional generic presence markers can be unavailable for workspace-scoped conversations; this does not prevent the authorized CRM view from opening.

The CRM starter leaves its header-action and template-picker slots empty.
The inbox contact panel and context menu do not display tag controls; saved tag and template data is retained.

### Minimal code-owned states

Register a bean in your application. Keep these IDs stable once records reference them:

```java
@Bean
CrmStateConfiguration crmStates() {
    var open = UUID.fromString("5720d440-43ee-4e55-82ea-202c67034001");
    var closed = UUID.fromString("5720d440-43ee-4e55-82ea-202c67034002");
    return new CrmStateConfiguration(List.of(), List.of(
        new CrmStateConfiguration.Status(
            new CrmStateConfiguration.Choice(open, "Open", "#3B82F6"), false),
        new CrmStateConfiguration.Status(
            new CrmStateConfiguration.Choice(closed, "Closed", "#64748B"), true)
    ), new CrmStateConfiguration.Transitions(open, open, closed, open));
}
```

Use `java.util.List`, `java.util.UUID`, Spring's `@Bean`, and
`su.onno.crm.service.CrmStateConfiguration`. Add contact-stage `Choice` values to the first list
when needed. With no state bean, the starter supplies no application-specific status choices.
