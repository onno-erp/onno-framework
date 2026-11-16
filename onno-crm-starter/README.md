# Composable CRM inbox

`su.onno:onno-crm-starter` adds conversations, messages, channel identities, delivery/retries and an
inbox widget around the host application's catalogs. It contains no customer or agent catalog,
sales pipeline, customer stages, tags, custom-field store, customer editor, or navigation layout.

Adding the dependency alone installs no CRM services or model package. Register a
`CrmCustomerBinding` bean to enable messaging, and `CrmInboxWorkspace` beans to grant inbox access.
The application owns its ordinary `@Catalog`, `EntityView`, `Page`, `Layout`, permissions and workflows.
If the host overrides `onno.scan-packages`, include `su.onno.crm` explicitly alongside its own packages;
otherwise the registered Boot packages are discovered automatically.

## Bind existing customers

```java
@Bean
CrmCustomerBinding<Customer> crmCustomers(CustomerRepository customers) {
    var catalog = new CrmCatalogBinding<>(Customer.class, customers::findActiveById)
        .field("description", "Name", "text", Customer::getFullName)
        .field("email", "Email", "email", Customer::getEmail)
        .field("membership", "Membership", "text", Customer::getMembership);
    return new CrmCustomerBinding<>(catalog);
}
```

Mappings use compiler-checked getters and expose only deliberately selected fields. No CRM base
class, copied customer, or fixed business-field set is required. `description` is the contact label;
`avatarUrl` optionally supplies its photo. Other keys are host-defined. Catalog read permissions
apply to every projected contact and inbox row; `.readableWhen((customer, principal) -> ...)` adds
an optional host record-level restriction. Deleted customers cannot be used.

A five-argument `.field(key, label, section, format, getter)` files a projection under a named
section; the contact panel groups its fields by section and draws one heading per group, so a host
exposing more than a handful of fields can say which belong together instead of handing the reader
one undifferentiated list. Fields declared with the four-argument form file under `Contact`.
On compact inbox layouts, the avatar opens a full-width contact page with a profile card and
separate cards for these field groups and linked identities. Its header stays fixed while the
content scrolls; Back returns to the chat. Desktop layouts show the same profile and field cards inside the contact sidebar.
`.color(key, getter)` supplies the colour a `badge` field's value carries — an enumeration already
names its own on `@EnumLabel` — so a stage reads as the same pill in the panel as it does elsewhere.
The colour travels beside the value as `<key>Color` and is not itself a displayable field.

```java
var catalog = new CrmCatalogBinding<>(Customer.class, customers::findActiveById)
    .field("stage", "Stage", "Pipeline", "badge", customer -> customer.getStage().label())
    .color("stage", customer -> customer.getStage().color());
```

Contact editing, searching, validation, lifecycle hooks, tags and business actions use the host's
normal catalog surfaces. The contact panel's **Open contact** button navigates to that catalog.
Add `detail.widget("Messages").type("crmContactDetails")` to its `EntityView` if wanted. Extra fields
are ordinary host `@Attribute` fields, not a second CRM schema.

Internally `Conversation.customer`, `ContactIdentity.customer` and `Conversation.assignee` store
UUIDs scoped to the explicitly bound host catalogs. `CrmCatalogBinding.ref(id)` produces a typed
`Ref<T>` for host code. Dedicated inbox reads resolve labels and customer links through the binding;
they never use `Ref<Customer>` pointing at a shadow CRM entity. The customer catalog identity is
persisted in `onno_crm_catalog_binding`; changing it requires an explicit link-data migration.

## Inbound contact policy

The read-only binding above cannot create contacts. To receive unknown channel peers, supply an
explicit host callback returning a saved `Ref<Customer>`:

```java
return new CrmCustomerBinding<>(catalog, incoming -> {
    Customer customer = customerCommands.resolveOrCreate(incoming);
    return Ref.of(Customer.class, customer.getId());
});
```

`IncomingContact` contains channel, connection key, external identity, name, email, phone, avatar and
source hints. The host decides whether to create, match, or reject. Its command owns required fields
and lifecycle validation. The callback runs in the inbound transaction; keep external effects out of
it. CRM checks scoped channel identities first, so repeat messages reuse the canonical customer.
Missing callbacks reject unknown peers instead of silently creating records. Email/name matching is
never an implicit CRM merge rule. Telegram profile images belong to channel identities and do not
overwrite host customer fields.

## Inbox workspaces and channel accounts

```java
@Bean CrmInboxWorkspace support() {
    return new CrmInboxWorkspace("support", "Support", Set.of("SUPPORT"),
        conversation -> Channel.EMAIL.equals(conversation.getChannel()));
}
```

Use the builder for separate read/write roles, a workspace-specific configuration function, the
authored list and facets. Write roles default to the read roles; configuration, list and facets are
optional:

```java
@Bean CrmInboxWorkspace sales(StageFacet stages) {
    return CrmInboxWorkspace.builder("sales", "Sales")
        .readRoles("SALES", "SALES_LEAD")
        .writeRoles("SALES_LEAD")
        .selection(conversation -> Channel.TELEGRAM.equals(conversation.getChannel()))
        .configure(config -> config.withFolders(List.of()))
        .list(list -> list.filter(Conversation::getSubject).label("Subject").contains())
        .facet(stages.facet())
        .build();
}
```

The positional constructors remain for compatibility. Workspace roles are application roles; there is no required `CRM_AGENT` or `CRM_MANAGER`
role. `ADMIN` is the framework superuser. Customer read permissions still apply. A persisted `Inbox`
represents a channel account; workspace predicates never change delivery destinations.

Compose a host page with `page.widget("Inbox").type("crmInboxWorkspaces").config("workspace","support")`.
The host chooses its route and navigation. No built-in `/pipeline`, inbox or settings page is installed.
Generic conversation/message/identity surfaces deny non-admin access so they cannot bypass workspace
membership. Internal repositories remain trusted application APIs.

## Optional capabilities

- **Assignment:** register `CrmAgentBinding<Employee>` around a `CrmCatalogBinding<Employee>` and a
  callback mapping `CurrentUser` to an active employee UUID. Use the existing identity record ID,
  not mutable email, where possible. Without the binding, assignment is unavailable.
- **Conversation statuses:** register `CrmStateConfiguration.catalog(...)` or `CrmStateConfiguration.enumeration(...)` with stable
  UUIDs, labels, colors and incoming/reply destinations. Without it, messages work with
  no status. No CRM status catalog or mirrored state rows are created.
  Customer lifecycle stages remain ordinary host fields.
- **Folders:** declare `CrmWorkspaceService.Folder` values through `Config.withFolders`. Empty by
  default; criteria intersect, and the first matching folder wins over the authorized loaded rows.
- **Personal chat groups:** register `CrmFeatures.defaults().withChatGroups(true)`. Disabled by default, with no group
  tables created and no group controls shown. Group membership never grants conversation access.
- **Connections:** place `CrmConnectionsView` (the `crmConnections` widget) wherever wanted. Only enabled connectors appear.
  `CrmChannelAccess` controls credential management and defaults to administrators only.
- **Sales:** the optional `example/src/sales/java` recipe is a catalog, stage enum, ordinary
  `EntityView` and three standard metric/count widgets. Build it with `-PsalesExample`; it is not
  part of the CRM artifact or the default inbox example.

`CrmWorkspaceCustomizer` configures presentation only. List selection is a framework primitive:
`ListSpec.selectionCheckboxes(true)` enables checkboxes on any ordinary catalog/document table.
Selected records use the existing top-toolbar and context-menu batch commands; select-all means
loaded rows, including expanded groups. CRM does not install a second bulk-action backend.

A host `CrmInboxWorkspace.Facet` adds a multi-select filter resolved from related data by a
`CrmInboxWorkspace.FacetResolver`. Construct it with `showOnRows=true` (the five-argument form) to
show each row's matching options as chips in the option's colour, for example contact tags. That is
not free: the resolver then runs on **every feed page over all conversations the principal may see
in the workspace** (it runs before filtering and pagination), so keep it to one or two batched
queries. Without `showOnRows` it runs only when the request filters on that facet. See
[Sidebar filter content](#sidebar-filter-content-and-pipeline-stages) for options that change at runtime.

Inbox toolbar filters are opt-in and use the ordinary `ListSpec` builder:

```java
new CrmInboxWorkspace("support", "Support", Set.of("MANAGER"), conversation -> true)
    .list(list -> {
        list.filter(Conversation::getSubject).label("Subject").contains();
        list.filter(Conversation::getLastMessageAt).label("Last message").dateRange();
    });
```

Options, multi-options, text, prefix and date ranges use the same toolbar controls and query
parameters as other lists. Filters narrow authorized workspace rows; they never grant access.
Declare option values explicitly in the feed's representation. No status/channel/priority chips
are installed automatically. Use `page.header(false)` when the list toolbar is sufficient.

## Message delivery contract

`CrmMessageTransport.supports(conversation)` selects applicable providers, including unavailable ones.
`CrmMessageTransport.connection(conversation)` reports connectivity, label, maximum text length, and
how many files the provider carries on one message (`maxAttachments`, with `attachmentReason` when
that is zero).
When unavailable, the label explains why replies are blocked. The composer disables replies while
checking, sending or unavailable, preserves drafts, and offers a connection recheck. Internal notes
remain independent of channel connectivity.
Messages may carry a sanitized `bodyHtml` (returned by the inbox and contact activity endpoints).
The inbox renders it in a script-less sandboxed frame with its own CSP, zoomed down to the bubble
width when the email is laid out wider; remote images load (so senders' open-tracking pixels fire),
and emails taller than 480 px start collapsed. Several
image attachments on one message are drawn as an album grid above the caption, and any image
opens a full-screen viewer (arrow keys or swipe to step, Escape to close).
`enqueue(conversation, message)` runs in the same transaction as the outgoing message. Connectors
write a durable outbox and deliver after commit. `CrmMessageRouter` rejects multiple providers
claiming one conversation. Retries apply only to failed outbound replies in that conversation.
`connection(conversation, principal)` (default: `connection(conversation)`) is the connection as
the replying user sees it; the delivery endpoint and the send command use it, so a provider can
offer a choice to one user only. `markedRead(conversation)` (default: nothing) is called inside the
transaction when `POST …/read` drops a conversation's unread count to zero; a provider mirrors it
after commit, best-effort (Gmail clears its `UNREAD` label).

### Attachments

Email replies expose separate From, To, and Subject rows. Transports opt into sender selection with
`Connection.withSenders(defaultId, List<Sender(id,label)>)`: the optional `from` command field must be
one of the offered ids (anything else is a 400), and the chosen or default id is saved on the
outgoing message as `senderAccount`, so delivery and retries use the same account. Gmail offers the
conversation's own mailbox (the default), the shared mailboxes and the replying user's own personal
mailbox, never another person's. Transports opt into recipient selection with `Connection.withRecipients(defaultAddress, allowedAddresses)`. Gmail offers the contact’s linked email identities and defaults to the current thread’s reply address. The optional `recipient` command field is validated against those choices and saved on the outgoing message, including the default, so retries retain the destination. Choosing a different address starts a new email thread within the same CRM conversation. A single address is shown without a dropdown. Email replies expose an editable Subject row separately from the sender account picker. The optional `subject` command field is saved on the outgoing message for delivery and retries; omit it to keep the existing reply subject. Gmail sends a changed subject as a new email thread and associates it with the same CRM conversation. Subjects must be a single line of at most 998 characters. Non-email channels reject subject overrides.

Files can be selected with the paperclip or dropped anywhere in the active conversation pane. The drop target respects reply permissions, channel attachment support, file count and upload size limits. Drops add files to the draft tray; they never send the reply automatically. Internal-note mode does not accept attachment drops.

An outbound reply may carry files. They are stored as references, never as bytes on the message:
the composer uploads each file through `POST /api/media` and sends back the returned URLs, which
`ConversationMessage.attachments` keeps newline-joined — the same shape the `gallery` field widget
persists. Only URLs this application's media endpoint issued are accepted, so nothing can point a
delivery worker at an arbitrary address; a link typed by hand is refused.

A reply may be files alone, with no covering note, and the conversation preview then names the
files. The contact activity feed carries the files on every message entry, because that feed — not
`GET /conversations/{id}/messages` — is what the chat pane renders from. At most ten files travel on one message, and a channel may allow fewer or none — the
composer offers a paperclip only where `maxAttachments` is positive and otherwise explains why.
Turning media ingestion off (`onno.media.enabled=false`) removes the capability entirely rather
than failing at send time; messages already carrying files still render.

Inbound files come from external senders, so `CrmAttachments.ingest` applies the same operator
policy as uploads: `onno.media.max-file-size` and `onno.media.allowed-content-types`. A refused
file throws `CrmAttachments.RejectedAttachment` (an `IllegalArgumentException`) with a `reason()` of
`EMPTY`, `TOO_LARGE` or `TYPE_NOT_ALLOWED`; a channel adapter should catch it, skip that file and
keep the rest of the message. Storage being disabled or failing is an `IllegalStateException`.

| Channel | Outbound files |
| --- | --- |
| Telegram | Images as photos, everything else as documents; a note up to 1024 characters rides as the caption, a longer one is sent first as its own message |
| Gmail | A `multipart/mixed` reply; refused above 25 MB of attachments, which is what Gmail accepts once base64 has inflated them |
| WhatsApp | Uploaded to WhatsApp's own media store and sent by id, so files work without publishing your media; JPEG/PNG under 5 MB as images, the rest as documents |
| Instagram | None — Instagram fetches attachments from a public URL, which the framework's media endpoint deliberately is not |

`onno-crm-channels-starter` supplies opt-in Telegram, Gmail, Instagram and WhatsApp adapters. It also
requires a host customer binding. Provider routing, deduplication, checkpoints and credential storage
remain connector-owned; all new-contact creation goes through the host inbound policy.

## Commands and read contracts

All browser commands use normal session authentication and CSRF. Each conversation read/command
requires `?workspace=<key>` and current membership; writes additionally require workspace write roles.

Authorization on a feed read is a batch: `CrmInboxWorkspaceService.accessible` applies the same rule
as `canAccess` to a whole list, asking which workspaces the principal may use once rather than per
row, and resolving contact readability through `CrmContactService.readable` — one pass over the
merge-redirect table instead of a query per conversation. An inbox of several hundred chats was
otherwise issuing that many queries to paint one page, and paging did not help, because the
authorization precedes it. `canAccess` remains for single-conversation checks.

| Route under `/api/crm` | Behavior |
| --- | --- |
| `GET /workspace` | `{workspace:{version,config,availableFields},canConfigure:false,customerCatalog}` |
| `GET /statuses` | Configured `{id,description,color,closed}` choices |
| `GET /contacts/{id}` | `{catalogName,fields,identities,conversations,canWrite}`; conversations are scoped |
| `GET /inbox-workspaces` | Permitted `{key,label,canWrite}` workspaces |
| `GET /inbox-workspaces/{key}` | Authorized rows with filters, cursor/limit, totals, config and permissions |
| `GET /inbox-workspaces/{key}/conversation/{id}` | Authorized conversations for the same customer |
| `GET/POST /inbox-workspaces/{key}/conversations/{id}/comments` | Internal notes. POST validates `body` against the shared `onno.comments.max-length` (4000 characters by default) and the CRM request cap of 8000 characters. Invalid text returns `422`. |
| `GET/POST /conversations/{id}/messages` | History / enqueue `{body,attachments,subject?,recipient?,from?}`; each message returns `attachments:[{url,filename,contentType,size,image}]` and the stored `subject`/`recipient`/`senderAccount` (null when not set), so history shows overrides. A `from` the delivery connection does not offer is `400` |
| `GET /conversations/{id}/delivery` | `{connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,senders,sender}`; `recipient` is the default address and `recipients` the choices (null/empty when the transport offers no selection); `senders` (`[{id,label}]`) are the accounts the reply may come from and `sender` the default id (empty/null when there is no choice), as offered to the caller. Addresses known only through another user's personal account are left out |
| `POST /conversations/{id}/status` | `{status}` (a configured status id) → the updated conversation state; a missing body or `status` is `400` |
| `POST /conversations/{id}/messages/{messageId}/retry` | Retry a failed reply |
| `POST /conversations/{id}/read` | Acknowledge unread messages |
| `GET/POST /contacts/{id}/activity` | Scoped timeline / record an internal activity; message entries carry the same `attachments` shape and `senderAccount` |
| `GET/POST /chat-groups?workspace=...` | Personal grouping, only when enabled |
| `GET /channels`, `POST /channels/{key}` | Credential-free status / authorized connector command |

There is no CRM contact CRUD, duplicate-search, merge-preview, merge or undo HTTP API. Host catalog
commands own business consolidation, authorization, auditing and reversal. A host consolidation
transaction may call `CrmContactService.transferLinks(source,target)` before deleting the source;
it transfers only conversations and identities, records a canonical redirect, and preserves each
conversation's provider destination. It does not move host comments, sales records or other business
references. This low-level operation has no automatic undo; a host must supply its complete reversal
policy if it exposes reversible merging.

CRM infrastructure stores the binding identity, contact serialization guard and canonical redirects;
personal group storage exists only when enabled. No customer-field or sales tables are installed.
There is no backward-compatibility layer for the former CRM-owned business model.

## UI extensions and verification

The packaged widget exposes `crm.chat.header`, `crm.chat.composer`, `crm.chat.aside`,
`crm.chat.context-menu`, `crm.contact.profile` (directly under the profile card) and
`crm.contact.actions`. Contact extensions receive the bound catalog name
and host permissions. Add buttons with the shared SDK extension registry; server authorization is
independent of widget visibility. Custom message renderers use `registerChatMessageRenderer`.

Run the bookstore with `--spring.profiles.active=crm` for a working existing-catalog example. It uses
`Customers`, `Employees` and `MANAGER`, without CRM customer/agent tables, pipeline or metrics.
Verify with `./gradlew :onno-crm-starter:test :onno-crm-channels-starter:test`, frontend tests,
`./gradlew clean check`, and `./gradlew publishToMavenLocal`.

## Connector and list composition

Channel fields are ordinary string attributes containing stable connector keys, not a closed
`CrmChannels` enumeration. `Channel` only supplies conventional string constants; third-party
connectors can use keys such as `acme.support-chat`. Register `CrmChannelDefinition` for a custom
channel, or implement `CrmChannelConnection.definition()` alongside setup and health commands.
`GET /api/crm/channels/types` returns only registered definitions. Branding belongs to connector
resources; unknown historical keys use a neutral icon.

CRM installs no Channels page. The host can place `CrmConnectionsView` (`crmConnections`) on any page. This optional widget
shows contributed connection descriptors; setup controls come from the ordinary
`crm.channel.settings` extension slot, with the connection in `context.record`, `canManage` in
permissions, and `refresh()` after a command. The bundled connector module owns its setup widget
and OAuth UI. Third-party connectors do not need changes to the CRM frontend.

A workspace accepts `.list(list -> ...)` or `.view(anEntityView)`. Both use the standard list resolver
for columns, labels, filters, search, sorting, page size and selection checkboxes. To open the inbox
renderer, declare `list.custom("crmInbox").label("Inbox").defaultView()`; otherwise the table is the
normal initial view. Workspace membership remains the server-side access boundary.

Bind statuses to a host model, for example:

```java
@Bean CrmStateConfiguration supportStates(StatusRepository repository) {
    return CrmStateConfiguration.catalog(SupportStatus.class, repository::findAllActive,
        SupportStatus::getColor, SupportStatus::isClosed, null);
}
```

Alternatively bind an ordinary `@Enumeration` with
`CrmStateConfiguration.enumeration(SupportState.class, s -> s == SupportState.DONE, null)`.
Pass optional `Transitions` to enable automatic incoming/reply destinations.
Declare a status filter explicitly with
`list.filter(Conversation::getStatus).label("Status").multiOptions(states.options())`.
Catalog choices are read live, and deleted choices cannot be selected. With no binding there is
no status selector, filter or table column and no transition commands. There is no CRM status editor.

Reply capability is independent of connectivity. The delivery response carries `connected`,
`replyCapability` (`AVAILABLE`, `READ_ONLY`, `WINDOW_CLOSED`) and `replyReason`.
For example, `new Connection(true, "Archive", 8000, ReplyCapability.READ_ONLY,
"This archive accepts incoming messages only.")` represents a healthy read-only channel.
A disconnected provider reports `connected=false`; workspace reply permission is checked separately.
The composer distinguishes these states, and reply/retry commands enforce `canSend()` on the server.

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

### Conversation pagination

The CRM workspace feed accepts `ids` (comma-separated or repeated UUIDs, at most 500) for live row refreshes. It intersects these IDs with workspace membership and read authorization before returning rows or counts. Reading a conversation therefore refreshes that conversation without replacing it with the first inbox row.

The root conversation pane and folder panes load the next keyset window as you scroll, with a
visible **Load more conversations** button for keyboard access and short panes. A failed page keeps
existing conversations visible and offers **Retry loading conversations**. All windows use the
workspace's authorized feed and current list filters; changing filters discards stale requests.

Inbox page reads authorize workspace members before counting or pagination. Queries using raw
conversation fields (including channel filters and message-time sorting) resolve full customer and
reference details only for the selected page. Customer-name search and display-field sorting retain
the fully decorated query path so their matching and authorization semantics remain unchanged.

Custom list renderers receive optional `ListRendererProps.total`, the server count matching the
current search and filters, independently of how many pages are loaded. A null/omitted total means
unknown. The inbox uses this count in its main chat header; folder counts remain scoped to loaded
rows and are labeled as the current view.

## Unified inbox controls and manual activity

For a workspace declaring ordinary `channel` and `inbox` option filters, enable the packaged
channel bar with `.config("channelFilters", "true")` on its `crmInboxWorkspaces` widget.
Channels remain visible; changing channel resets the account selection. Set
`.config("accountFilter", "false")` to hide the account picker while keeping the channel bar. The account picker is
restricted to accounts with readable conversations in the workspace, before search, filters and
pagination. These are conversation accounts, not a claim that a connector is online. The response's
`list.channelAccounts` contains `{id, channel, label}` choices. Empty connected accounts still belong
in connector settings until the host gives them explicit workspace visibility.

### Inbox toolbar contributions

The inbox header carries a `crm.inbox.toolbar` extension outlet, so an application can put its own
control beside the workspace switcher instead of waiting for the CRM to grow one. A contribution
receives `record.folders` (how many folders this workspace has) and `record.grouping` (the widget's
`grouping` config) so it can decide whether it belongs on this particular inbox, and
`context.view` — a small string map describing how the inbox is currently being read, which the
renderer acts on.

The CRM's own **Folders / Everything** switch is registered through that outlet as
`onno.crm.inbox.grouping`; it writes `view.grouping`, and "Everything" drops the grouping without
dropping any chat. Registering a contribution under the same id replaces it, and
`.config("grouping", "false")` hides it where the folders carry the workspace's meaning rather than
a reading preference — a sales pipeline whose folders are its stages, for example.

```tsx
registerExtension({
  id: "acme.inbox.unassigned",
  slot: "crm.inbox.toolbar",
  component: ({ context }) => (
    <Button size="toolbar" aria-pressed={context.view?.get("owner") === "none"}
      onClick={() => context.view?.set("owner", context.view.get("owner") === "none" ? undefined : "none")}>
      Unassigned
    </Button>
  ),
});
```

### Folder identity

A folder draws the plain folder glyph unless the workspace gives it one of its own:

```java
CrmWorkspaceService.Folder.conversations("booked", "Booked", ids).withIcon("party-popper", "#059669")
```

`icon` is a lucide glyph name, drawn through the shell's icon bridge; `color` is `#rrggbb`, used as
the glyph's colour over a faded ground. Both are validated server-side and an unrecognised value
falls back to the plain folder rather than rendering. Use it where the folders mean something the
application already knows — a pipeline whose folders are its stages, each with the colour the
planning team chose. Deliberately explicit rather than inferred from the label's wording: an
inferred identity stops working the moment that label is written in another language.

The controls use `EntityListWidget.queryParams` for additional server-validated query constraints,
so pagination and ordinary text/date filters continue to run on the server. This property does not
grant access or replace workspace selection rules.

`Folder.empty(key, label)` displays an intentionally empty category (`matchNone: true`). Existing
folder constructors retain their match-all semantics for empty criteria; the host owns category names
and contact classification. Use a workspace configuration function to derive membership from host data.

The conversation header's **Log activity** action records a phone call or meeting summary through
`POST /api/crm/contacts/{id}/activity`, using the existing internal system-event model. An optional
HTTPS recording link is stored with the summary; this is manual entry, not note-taker ingestion.
The operation requires write access and rejects application read-only mode. Activity entries now
include `accountLabel` from the readable conversation's inbox. Messages display this account context.

Add `.config("inboxRoute", "/inbox")` to a host contact's `crmContactDetails` widget to expose
**Open conversation**. It opens the authored inbox with `?conversation={id}`; the server verifies
workspace and contact access before returning that contact's channels. Existing **Open contact**
navigation returns to the bound host catalog. Choose a route whose workspace includes those contacts.


The **Schedule Zoom** header control saves an existing HTTPS Zoom meeting link, future local date/time,
duration and explicit time zone/UTC instant as a `MEETING_PLANNED` activity. It prepares an invitation
in the reply composer without sending it. This prototype flow does not create a Zoom meeting or calendar
booking; provider-backed scheduling remains a connector concern.

Logged calls and meetings render as native timeline cards with a summary, author/time and an optional
recording link. Long summaries expand on demand. Existing internal-event text remains compatible;
other system events retain the compact timeline treatment. Summaries are manually entered, not AI-generated.

### Responsive chat workspace

The inbox uses its allocated pane width, including split views, to choose its layout. At 720px and above the conversation list and chat sit side by side. Below 720px the list opens first; selecting a contact opens the chat with a Back to conversations control. Returning to the same conversation preserves the draft. Channel filters collapse into a selector in narrow panes.

The composer occupies normal layout space below the independently scrolling history, so it cannot cover messages. Contact details use a third column in wide panes (1100px and above) and an overlay in smaller panes, with focus moved to its close button and restored on dismissal. Conversation actions are available from the header's overflow control. Read-only and disconnected channels retain their explanatory status without an empty reply editor.

Mobile list-to-chat navigation uses the same 300ms horizontal slide as folders, reversed on Back. Offscreen panes are inert and hidden from assistive technology. Reduced-motion preferences disable both transitions.

### Composer drafts, large window and sending mailbox

Drafts belong to the conversation (`widgets/composerDrafts.ts`): the reply, an edited subject, the
pinned recipient, the chosen sending mailbox and reply-or-note mode survive switching chats,
contacts, inbox modes and tabs. The text parts are kept in `localStorage` under
`onno.crm.draft.v1|<workspace>|<conversation>`, best effort (guarded access, 64 000 characters per
entry, at most 100 entries, dropped after 30 days); files already uploaded for the next reply stay
in memory only. A draft is cleared after a successful send or when its author empties it. Clicking
a contact opens the conversation that holds its unsent draft, and the page asks before unloading
while text or files written in this session are unsent. `insertDraft` contributions append to the
open conversation's draft as before.

The inline reply grows with its text up to `min(240px, 40vh)`. **Open in a larger window** edits the
same draft in a `DialogShell` sized to about 90vw × 85vh, with a toggle that fills the screen; on
phones (under 640px) it always fills the screen. Closing it returns focus to the inline field.

When the delivery connection lists two or more `senders` (`{id, label}`), the From row becomes a
mailbox picker defaulting to `sender`, and the send command carries `from: <sender id>`. Switching to
the contact's other conversations then moves to a Channel row of its own. Without `senders` (or with
one) the From row stays the conversation switch and no `from` is sent.

### Visible group controls and mailbox search

The conversation list carries a **New group** button (personal chat groups enabled), which creates a
group exactly as the row menu does — with the open chat in it, because the server starts every group
with a chat — and **Find in mailbox** when `GET /api/crm/gmail/accounts` lists at least one mailbox.
Each row has a "⋯" button opening the same menu as right-click: shown on hover or keyboard focus,
always on touch screens. Labels resolve through `crm.group.*`, `crm.chat.rowMenu` and `crm.mailbox.*`
messages, so an app can call groups folders.

**Find in mailbox** searches with Gmail syntax (`GET /api/crm/gmail/search?account=&q=&pageToken=`),
shows sender, subject, date, snippet and an Archived badge per message, pages with Load more, and
either opens the message's conversation or restores it (`POST /api/crm/gmail/restore` with
`{account, messageId, toInbox: true}` → `{conversationId, movedToInbox}`). A conversation the loaded
feed does not hold is opened by narrowing the feed to its contact, like `?conversation=`, with an
**All chats** button to widen it again. A mailbox with `canModify: false` is said up front to keep
restored mail archived in Gmail. All mailbox paths live in `widgets/mailboxApi.ts`
(`MAILBOX_ENDPOINTS`).

### Inbox toolbar controls

Channel/account selectors now live inside the Inbox toolbar, in a responsive secondary row. Widget configuration `toolbarTitle`, `toolbarCount`, `toolbarViews`, and `toolbarSearch` accept `"false"` to hide each presentation element (defaults remain visible). `channelFilters` and `accountFilter` continue to control the selectors. Hiding controls does not alter permissions or clear filters. Composed list widgets can use `EntityListWidget.toolbarContent` for a full-width toolbar row and `toolbarOptions` for title/count/search/view-switch visibility; `headerExtra` remains the compact title-adjacent slot.


Explicit toolbar composition uses comma-separated widget settings. Omitted settings preserve defaults; empty control/filter/action/secondary lists hide that group.

| Setting | Values |
| --- | --- |
| `toolbarControls` | `title,count,search,filters,views,grouping,inputs,actions,create,extensions,workspaces` |
| `toolbarViewOptions` | Allowed bodies: `table,map,custom`. Only declared/registered bodies are offered. A single view removes the switcher and selects that body, overriding an unavailable initial/default view. An empty or invalid list falls back to available views. |
| `toolbarFilters` | Exact filter keys to display, e.g. `status`. |
| `toolbarActions` | Exact server-defined toolbar action keys. Permissions still apply. |
| `toolbarSecondary` | `channels,accounts`, either one independently, or empty to omit the lower row. |

These settings select contents, not visual ordering. The host retains consistent title/filter/action positions. For example, a support inbox might use `toolbarControls=title,search,filters`, `toolbarViewOptions=custom`, `toolbarFilters=status`, and `toolbarSecondary=channels,accounts`; its Conversations body then remains available without Table/Conversations buttons. The reusable `EntityListWidget.toolbarOptions` exposes equivalent `controls`, `views`, `filterKeys`, and `actionKeys` arrays. Visibility is presentation, never authorization.

### Workspace chrome configuration

CRM uses the shared SDK `WorkspaceToolbar`, so layout choices belong to the host application.
For `crmInboxWorkspaces`, `toolbarPlacement=renderer` moves the framework's existing list
controls into the conversation pane and removes the separate top toolbar. It remains
reachable for empty filtered feeds. Existing `toolbarControls`, `toolbarFilters`,
`toolbarActions`, `toolbarViewOptions`, and `toolbarSecondary` allowlists still apply.
The default placement is `header` for compatibility.

The following widget config values are JSON objects using the shared toolbar regions
`start`, `center`, `end`, `secondary`, `overflow`, and `hidden` (arrays of item IDs):

| Config | Available item IDs |
| --- | --- |
| `listToolbar` / `mobileListToolbar` | `title`, `count`, `controls` (the host list toolbar; used with renderer placement) |
| `chatToolbar` / `narrowChatToolbar` / `mobileChatToolbar` | `back` (compact only), `avatar` (desktop only), `title`, `contact`, `openContact`, `scheduleCall`, `logActivity`, `extensions`, `actions` (legacy expandable row) |
| `composerToolbar` / `mobileComposerToolbar` | `mode`, `attach`, `send`, `extensions`, `tools`, `channel` |

Desktop configs override the built-in layout; mobile configs override its individual
regions. Replace both source and destination arrays when moving an item. `hidden` wins;
unknown IDs are ignored. Missing or malformed config uses defaults. Layout never grants
access to unavailable commands. Keep `back` reachable on mobile detail screens.

```java
page.widget("Inbox").type("crmInboxWorkspaces").config("workspace", "support")
    .config("toolbarPlacement", "renderer")
    .config("toolbarControls", "search,filters")
    .config("toolbarSecondary", "channels,accounts")
    .config("chatToolbar", "{\"start\":[\"back\"],\"center\":[\"title\"],\"end\":[\"contact\"],\"overflow\":[\"openContact\",\"scheduleCall\",\"logActivity\",\"extensions\"]}");
```

Set `mobileToolbar=sheet` to use the host's compact search-and-filter-sheet presentation.
This keeps the mobile conversation header short: search stays visible, while status and
channel/account choices move into the filter sheet. Desktop layout is unchanged.

Chat header actions can be placed individually in `end` or `overflow`. Keep `back` in `start`
and the contact identity in `center`; avoid placing secondary actions beside Back. Each action
retains its configured permissions. The `actions` item remains available for existing layouts.

Mobile composers can place `tools` in `start`, `channel` in `center`, and `send` in `end`.
This puts the reply/note switch above the editor and the sending account alongside the bottom
actions. `tools` opens the shared responsive action panel with meeting, activity, attachment and header extensions.
Set `mobileChatToolbar.overflow` to `[]` to keep those tools out of the mobile header.

The desktop chat header stays on one row. When it runs out of width it first hides optional
button labels (elements marked `data-toolbar-label`, e.g. "Log activity") and keeps their icons
and tooltips; the customer name keeps a minimum width. If it still overflows, `narrowChatToolbar`
(`CrmInboxView.header(Breakpoint.NARROW, ...)`) replaces the desktop regions, typically moving `extensions` to
`secondary` so they wrap onto a second row. Header extensions should mark their own optional
label text with `data-toolbar-label` so they condense with the built-in actions.

### Conversation row presentation

Conversation rows use `ConversationRowContent` slots for avatar, title, timestamp, preview,
optional metadata, badges and a trailing indicator. Rows default to a single preview line,
a channel badge on the avatar and no separate account-label row. Configure the workspace
widget with `conversationRow` JSON, for example
`{"showAccount":true,"showChannelBadge":false,"previewLines":2}`.
Invalid options fall back to compact defaults. Existing list-title/list-preview bindings
and avatar/timestamp visibility settings continue to apply.

Optional `conversationRow.badges` rules have `field`, `equals`, and `label`; a rule displays
when the readable row field's display value matches. These are presentation labels, not stored
entity tags. For example `{"badges":[{"field":"statusDisplay","equals":"Urgent","label":"Priority"}]}`.
No row is reserved when no rule matches. Keep demo-contact rules in the consuming app.

Desktop chat headers use flexible space for inline actions. The `avatar` item shows the contact
photo; `contact` is an icon button that toggles the details panel. Compact headers retain the
centered name and tappable avatar.

Compact inbox filters (`mobileToolbar=sheet`) show status views, channels with provider logos, and accounts as directly selectable navigation rows. Selecting a channel clears the account selection and restricts the account list to that channel. Search remains in the conversation header and filters apply immediately.

For a docked desktop panel, set `CrmInboxView.filterSidebar(true)` (or widget config `filterSidebar=true`) together with `toolbarPlacement(ToolbarPlacement.IN_LIST)` (`toolbarPlacement=renderer`). A persistent 208px navigation column appears to the left of the inbox when its container is at least 1100px wide, open by default. The conversation header’s navigation toggle collapses or expands it with a width transition, preserving selected filters; reduced-motion preferences disable the transition. The application-level `filterSidebar(false)` still removes the panel entirely. Narrower containers use the shared filter sheet; phones get a bottom sheet. Selections stay in the list query state when the panel closes or changes presentation. The option is off by default and uses the same configured filters and secondary controls. Use `filterSidebar(false)` to remove the desktop panel; with `mobileToolbar(MobileToolbar.SHEET)`, search and the Filters button remain in the conversation header. Desktop navigation uses compact 32px rows; touch controls retain 44px targets. Set `connectionsRoute("/connections")` to add a Manage connections footer linking to an app-owned page (omit `/ui`). Navigation only lists channels and accounts supplied by the authorized workspace; it does not infer totals from a paginated conversation list.

Messages are acknowledged once their history is loaded and the conversation is visible; contact
metadata and transport availability load independently. Successful acknowledgements clear the
local unread badge immediately while the shared feed catches up. Failed acknowledgements preserve
unread state. A newer server row supersedes the local read snapshot.

The attachment action remains visible in reply mode. When unavailable, the button is disabled
and its wrapper exposes the channel/permission/file-limit reason on hover. It opens the file picker only when attachments are allowed.

## Scoped connection accounts

Hosts still author the route and navigation. The `crmConnections` widget presents shared accounts,
personal accounts, provider availability, loading skeletons, retries, and account management.
Place it with the typed `CrmConnectionsView`:

```java
import su.onno.crm.ui.CrmConnectionsView;
import static su.onno.crm.service.CrmConnectionAccounts.Scope.*;

p.view(new CrmConnectionsView().title("Connections")
    .scopes(SHARED, PERSONAL)
    .defaultScope(SHARED)
    .sharedLabel("Team accounts")
    .personalLabel("My accounts")
    .providers("gmail", "telegram", "instagram", "whatsapp")
    .showUnavailable(true));
```

Scopes are `CrmConnectionAccounts.Scope` values, the same enum the accounts API and provider
descriptors use. It emits the `crmConnections` widget config keys `scopes`, `defaultScope`, `sharedLabel`,
`personalLabel`, `providers` and `showUnavailable`; the older type name `crmSettings` renders the same
widget, so raw `p.widget(...).type("crmSettings")` configuration remains supported. `defaultScope` must be one of `scopes`. Like `CrmInboxView`, it
freezes on first placement. All options are optional. `providers` is an ordered-independent display allowlist; omitted means all
installed providers. `scopes` controls the scope switcher and its order. These are presentation
options, not access rules. No page or sidebar destination is installed by the starter.

`CrmAccountProvider` beans contribute provider descriptors even when disabled: branding, description,
supported scopes, availability, and setup guidance. Their account views and commands remain
connector-owned. The existing `crm.channel.settings` extension receives `accountId` for managed
accounts, the provider key, scope, a server-derived `canManage`, and `refresh()`. Legacy
`CrmChannelConnection` beans remain visible as shared accounts and retain `/api/crm/channels`.
Provider keys must be unique. Replace a bundled descriptor with a bean named
`gmailAccountProvider`, `telegramBusinessAccountProvider`, `telegramAccountProvider`,
`instagramAccountProvider`, or `whatsappAccountProvider`; register a new key for a third-party
connector. `Descriptor.scopes` is a `List<CrmConnectionAccounts.Scope>` (`SHARED`, `PERSONAL`);
empty means the adapter only runs on one server-configured shared account.

**Personal accounts are opt-in.** Shared accounts work out of the box. To let users connect their
own accounts, register `CrmFeatures.defaults().withPersonalAccounts(true)`. Until then the API
withholds `PERSONAL` from provider descriptors and rejects personal creation, whatever providers
advertise.

The credential-free `onno_crm_connection_accounts` table stores a UUID, provider key, immutable
`Scope` (`SHARED`/`PERSONAL`), server-assigned owner (the authenticated principal's name, i.e. the
OIDC subject), display label, and bound inbox UUID (one account per inbox). Like the adapters'
state tables it is internal module state created on startup, not a catalog.

Who may do what:

- **Shared accounts** are managed through `CrmChannelAccess.canManage` (ADMIN by default).
- **Adding a personal account** needs the feature, `CrmChannelAccess.canManagePersonal` (any signed-in
  user by default; override the bean to restrict) and write access to an inbox workspace.
- **An existing personal account** is managed only by its owner, never by administrators. The owner
  keeps control (rename, pause, disconnect) even after losing inbox access or the personal policy,
  so a mailbox cannot keep syncing out of its owner's reach. There is no administrator revoke yet:
  to cut off a departed user's mailbox, revoke the provider grant (e.g. the Google OAuth token).

`GET /api/crm/connection-accounts` returns `Listing{providers, accounts, canManageShared,
canManagePersonal}`; each account is `AccountView{id, provider, scope, label, canRemove,
connection, canManage}`. No credentials or other users' personal accounts are returned. `POST`
accepts `{provider, scope, label}`; `POST /{id}` accepts `{action, label?}`. `rename` and
`removeDraft` are handled by the CRM and are reserved; any other action is forwarded to the
provider when the account's view offers it. Only an unconnected draft can be removed. Disconnect
preserves ownership and conversation history. A failing provider action answers 422 with a
generic message; the cause is logged server-side because upstream errors can quote credentials or
addresses.

`CrmInboxWorkspaceService` is the single place that applies personal inbox ownership: `canAccess`
(one indexed lookup) and its batch form `accessible` gate every conversation read, and
`visibleIdentities` filters contact identities. Scoped endpoints, contact projections, the activity
feed, attachments and commands all go through it; loops must use the batch form.
A host subclass of `CrmInboxWorkspaceService` must pass `CrmConnectionAccounts` to the super
constructor; it is required so a subclass cannot switch the privacy rule off by omission.

**Provider contract:** contact identities created for a managed account must use the account's
inbox UUID (`account.inboxId().toString()`) as `IncomingContact.connectionKey`. That is how
`visibleIdentities` recognises identities seen only in someone else's personal inbox.

Generic CRM catalogs (`CrmConversations`, `CrmConversationMessages`, `CrmContactIdentities`,
`CrmInboxes`), exports, and generic entity events are never open to non-administrators. With
personal accounts enabled they are closed to ADMIN as well, because their contracts have no
per-record principal filter; use the scoped CRM APIs. This is configuration, not data, so it does
not change at runtime when someone connects an account. `UiEntityAccessPolicy.includesAdministrators()`
is the opt-in for such privacy boundaries; other policies keep the ordinary ADMIN bypass.

Contacts are not private. A correspondent who writes to a personal inbox is resolved into the host's
shared contact catalog under the host's own permissions, so colleagues may see that the contact
exists, but not the personal conversation or its channel identity.

Bundled Gmail and Telegram business support shared and personal accounts. The other bundled
adapters run on one shared, server-configured account and advertise that limitation instead of
offering nonfunctional personal setup. Provider capability claims must match their actual transport
and credential isolation.

### Typed inbox presentation

Use `CrmInboxView` instead of handwritten widget JSON for new inbox pages. It is a final class
implementing core `PageView`; declare it as a `@Bean` and inject it into the `Page` that places it:

```java
import su.onno.crm.ui.CrmInboxView;
import static su.onno.crm.ui.CrmInboxView.Breakpoint.*;
import static su.onno.crm.ui.CrmInboxView.HeaderItem.*;

@Bean
CrmInboxView supportInboxView() {
    return new CrmInboxView("support")
        .toolbarPlacement(CrmInboxView.ToolbarPlacement.IN_LIST)
        .filters("status", "pipelineStage")
        .controls(CrmInboxView.Control.SEARCH, CrmInboxView.Control.FILTERS)
        .views(CrmInboxView.View.INBOX)
        .secondary(CrmInboxView.Secondary.CHANNELS, CrmInboxView.Secondary.ACCOUNTS)
        .header(h -> h.start(AVATAR).center(TITLE).end(CONTACT, SCHEDULE_CALL))
        .header(NARROW, h -> h.start(AVATAR).center(TITLE).end(CONTACT).secondary(SCHEDULE_CALL))
        .header(MOBILE, h -> h.start(BACK).center(TITLE).end(CONTACT))
        .composer(MOBILE, c -> c.start(CrmInboxView.ComposerItem.TOOLS).end(CrmInboxView.ComposerItem.SEND));
}
@Bean Page inbox(CrmInboxView inbox) { /* compose: p.header(false); p.view(inbox); */ }
```

Each surface has one method plus an optional `Breakpoint`: `header(...)` (chat header;
`DEFAULT`, `NARROW`, `MOBILE`), `listHeader(...)` (`DEFAULT`, `MOBILE`) and `composer(...)`
(`DEFAULT`, `MOBILE`). Unsupported combinations such as `composer(NARROW, ...)` throw
`IllegalArgumentException`. They emit `chatToolbar`/`narrowChatToolbar`/`mobileChatToolbar`,
`listToolbar`/`mobileListToolbar` and `composerToolbar`/`mobileComposerToolbar`.

Every configured toolbar is a **full replacement**: all six regions are serialized, unset regions
as `[]`, so renderer defaults never leak in and nothing needs an empty `.overflow()` to clear it.
An item may appear in only one region. `MOBILE`/`NARROW` variants also replace the `DEFAULT`
toolbar rather than patching it; an unconfigured variant falls back to `DEFAULT`.

Enum constants map to renderer ids (`OPEN_CONTACT` → `openContact`). `View.INBOX` emits `custom`
(the conversation renderer); `ToolbarPlacement.IN_LIST` emits `renderer` and `PAGE_HEADER`
emits `header`. `CrmRendererContractTest` reads the TypeScript renderers and fails when a Java
key is not one they understand.

Configure once at startup: the first placement freezes the view, and later configuration calls
throw `IllegalStateException`. Each placement produces an identical widget descriptor. The constructor
selects the workspace key; the workspace still defines access and ListSpec filters/facets.
`filters(...)` selects declared filter keys, while `secondary(...)` selects channel/account
controls. At startup `CrmInboxViewValidator` checks every `CrmInboxView` **bean**: an unknown workspace
key fails startup, and so does a filter key the workspace bean does not declare as a ListSpec filter
or facet. Facets are declared on the workspace bean even when their options change at runtime (see
`FacetOptions` below), so every valid key is known at startup. Views constructed inline inside `Page.compose` are not visible to it. `rows(...)` configures previews, account/channel badges and
conditional row labels without JSON. Existing `crmInboxWorkspaces` widget configuration remains
compatible (raw JSON still merges mobile regions over desktop ones); the class emits that same
contract and does not change provider availability.

### Sidebar filter content and pipeline stages

The sidebar is a presentation of the workspace's declared filters, not a fixed CRM funnel.
`CrmInboxView.filters("status", "pipelineStage")` chooses the visible groups and their order;
`filters("pipelineStage")` shows only the pipeline, and `filters()` hides all filter groups.
`secondary(Secondary.CHANNELS, Secondary.ACCOUNTS)` independently selects channel/account navigation;
`secondary()` hides both. `filterSidebar(false)` switches off the desktop navigation card.
Labels come from each filter declaration. Option colours are rendered in both the sidebar and sheet.
Conversation status colours come from the app's `CrmStateConfiguration` binding.

Use `builder(...).facet(new CrmInboxWorkspace.Facet(key, label, options, resolver))` (or the
`facet(...)` wither on an existing workspace) for a filter derived from related business records,
such as deal stages:

```java
var stage = new CrmInboxWorkspace.Facet("pipelineStage", "Pipeline stage",
    List.of(new CrmInboxWorkspace.FacetOption("WON", "Won", "#10B981")),
    (conversations, principal) -> stagesByConversation(conversations, principal));
```

When the options are runtime data — an editable tag library, stages users can rename — pass a
`CrmInboxWorkspace.FacetOptions` source instead of a list. It is evaluated once per inbox read, so
declare the facet statically on the workspace bean; do not subclass `CrmInboxWorkspaceService`:

```java
var tags = new CrmInboxWorkspace.Facet("tags", "Tags",
    () -> tagLibrary.list().stream()
        .map(tag -> new CrmInboxWorkspace.FacetOption(tag.id().toString(), tag.name(), tag.color())).toList(),
    (conversations, principal) -> tagsByConversation(conversations, principal),
    true); // showOnRows
```

While a source yields no options the facet is left out of the filter metadata and adds no row chips;
its key stays valid, so a stale `in=tags,...` link matches nothing rather than failing.

Each `FacetOption(value, label, color)` has a stable wire value and optional `#RRGGBB` colour.
The resolver receives authorized conversations and the principal, and returns a map from conversation
ID to matching values. It must enforce access to any related records it reads. It runs once per
requested facet, before filtering, totals and pagination. Multiple values within one group are OR;
different groups combine with AND. It never expands workspace or personal-account access.
A contact linked to several business records may contribute several stage values. Missing links
match no stage, while All applies no stage constraint. Facet keys must not collide with each other,
with the authored list's filters, with any `Conversation` field name (inherited catalog fields such
as `id`, `code` and `description` included), or with a key the feed computes for each row
(`<field>Display`, `<field>Color`, `<field>Ref`, `customerAvatar`, `rowBadges`). Every
`CrmInboxWorkspace` constructor and the builder reject such facets, and null facets, at construction.

Keep stage definitions in the host app's catalog or enumeration. A deals app, for instance, derives
its pipeline options and colours from its own `Stage`, resolving open deals through their primary
contact; the conversation lifecycle remains a separate `CrmStateConfiguration`.

Messages may contain attachments without a text body. Validation requires at least text or a
file reference; the Body attribute is optional so file-only replies reach channel delivery.

### Voice messages

The shared inbox plays `audio/*` attachments inline, with onno-themed playback controls (play/pause, seek, time and speed) and a download
link when a browser cannot decode the file. Playback is manual and audio is not preloaded. Existing
Telegram voice imports use this renderer without a data migration.

Audio files can still be attached through the existing file picker and delivered through channel
attachment transports. Browser recording and transcription are not included. Playback support
depends on the browser and audio format.
