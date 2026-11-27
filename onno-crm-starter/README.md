# Composable CRM inbox

`su.onno:onno-crm-starter` adds conversations, messages, channel identities, delivery/retries and the
inbox screens (the npm package `@onno/crm`, in `src/main/ui`) around the host application's catalogs. It contains no customer or agent catalog,
sales pipeline, customer stages, tags, custom-field store, customer editor, or navigation layout.

Adding the dependency alone installs no CRM services or model package. Register a
`CrmCustomerBinding` bean to enable messaging, and `CrmInboxWorkspace` beans to grant inbox access.
The application owns its ordinary `@Catalog`s, permissions and workflows, and its React pages and
navigation. Java decides which conversations a workspace holds and who may act on them; how the
inbox presents is the props of `@onno/crm`'s components.
If the host overrides `onno.scan-packages`, include `su.onno.crm` explicitly alongside its own packages;
otherwise the registered Boot packages are discovered automatically.

## Bind existing customers

```java
@Bean
CrmCustomerBinding<Customer> crmCustomers(CustomerRepository customers) {
    var catalog = new CrmCatalogBinding<>(Customer.class, customers::findActiveById)
        .field("description", Customer::getFullName)
        .field("email", Customer::getEmail)
        .field("membership", Customer::getMembership);
    return new CrmCustomerBinding<>(catalog);
}
```

Mappings use compiler-checked getters and expose only deliberately selected fields. No CRM base
class, copied customer, or fixed business-field set is required. `description` is the contact label;
`avatarUrl` optionally supplies its photo. Other keys are host-defined. Catalog read permissions
apply to every projected contact and inbox row; `.readableWhen((customer, principal) -> ...)` adds
an optional host record-level restriction. Deleted customers cannot be used.

How the contact panel labels, groups and formats these fields is `@onno/crm`'s `contactFields`
prop (default: every bound field, labelled from its key, its format read from its value — an
address becomes a mail link, a URL a link); see [The inbox UI](#the-inbox-ui-onnocrm).
On compact inbox layouts, the avatar opens a full-width contact page with a profile card and
separate cards for the field groups and linked identities. Its header stays fixed while the
content scrolls; Back returns to the chat. Desktop layouts show the same cards inside the contact sidebar.
`.color(key, getter)` supplies the colour a field's value carries — an enumeration already names its
own on `@EnumLabel` — so a stage drawn as a `badge` reads as the same pill in the panel as elsewhere.
The colour travels beside the value as `<key>Color` and is not itself a field.

```java
var catalog = new CrmCatalogBinding<>(Customer.class, customers::findActiveById)
    .field("stage", customer -> customer.getStage().label())
    .color("stage", customer -> customer.getStage().color());
```

Contact editing, searching, validation, lifecycle hooks, tags and business actions use the host's
normal catalog surfaces. The contact panel's **Open contact** button navigates to that catalog.
Add `recordPanels: [{ component: CrmContactDetails, title: "Contact" }]` to its entity customization
if wanted. Extra fields
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

Use the builder for separate read/write roles, a workspace-specific configuration function and
facets. Write roles default to the read roles; configuration and facets are optional:

```java
@Bean CrmInboxWorkspace sales(StageFacet stages) {
    return CrmInboxWorkspace.builder("sales", "Sales")
        .readRoles("SALES", "SALES_LEAD")
        .writeRoles("SALES_LEAD")
        .selection(conversation -> Channel.TELEGRAM.equals(conversation.getChannel()))
        .configure(config -> config.withFolders(List.of()).without("logActivity"))
        .facet(stages.facet())
        .build();
}
```

Workspace roles are application roles; there is no required `CRM_AGENT` or `CRM_MANAGER`
role; workspace role names compare normalized and, with `RoleGrants` declared, must be declared roles or
`fromIdp` names (startup fails otherwise). Only an explicit `crm.administer` grant
(`CrmPermissions.ADMINISTER`) opens every workspace; no role name does. Customer read permissions still apply. A persisted `Inbox`
represents a channel account; workspace predicates never change delivery destinations.

A workspace's `Config` is behaviour: the conversation actions it enables (`CrmWorkspaceService.ACTIONS`:
`logActivity`, `edit`, `details`, `reply`, `note`, `sendReply`, `sendNote`, `retry`) and its folders.
Render it with `<CrmInbox workspace="support" />` on a page of the app's own. The host chooses its route and navigation. No built-in `/pipeline`, inbox or settings page is installed.
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
  A folder's glyph and hue are the inbox's `folders` prop.
- **Personal chat groups:** register `CrmFeatures.defaults().withChatGroups(true)`. Disabled by default, with no group
  tables created and no group controls shown. Group membership never grants conversation access.
- **Connections:** render `<CrmConnections setups={[channelSetups]} />` wherever wanted. Only enabled connectors appear.
  `CrmChannelAccess` controls credential management and defaults to explicit `crm.administer` holders.
- **Sales:** model opportunities, stages and metrics in the host application with ordinary
  catalogs, enumerations and React pages. They are not part of the CRM artifact.

`CrmWorkspaceCustomizer` beans adjust the default `Config` (actions, folders) for every workspace.

A host `CrmInboxWorkspace.Facet` adds a multi-select filter resolved from related data by a
`CrmInboxWorkspace.FacetResolver`. Construct it with `showOnRows=true` (the five-argument form) to
show each row's matching options as chips in the option's colour, for example contact tags. That is
not free: the resolver then runs on **every feed page over all conversations the principal may see
in the workspace** (it runs before filtering and pagination), so keep it to one or two batched
queries. Without `showOnRows` it runs only when the request filters on that facet. See
[Sidebar filter content](#sidebar-filter-content-and-pipeline-stages) for options that change at runtime.

Inbox toolbar filters are the inbox's `list.filters`, as for any entity list:

```tsx
<CrmInbox workspace="support" list={{ filters: [
  { field: "subject", label: "Subject", type: "contains" },
  { field: "lastMessageAt", label: "Last message", type: "dateRange" },
  { field: "status", label: "Status", type: "multiOptions" },   // choices come from the binding
] }} />
```

The feed accepts the ordinary list operators on any conversation field the field's type supports
(`eq`/`in` everywhere, `like`/`prefix` on text, `ge`/`le` on numbers and dates) and on facet keys.
Filters narrow authorized workspace rows; they never grant access. The status, priority and facet
choices travel in the feed's default list descriptor, so a filter on those fields needs no options.

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

Email replies also have a **Cc** row. Transports suggest who to copy with `Connection.withCc(List<String>)` (`cc` on the
delivery response, `Name <address>` entries); Gmail suggests reply-all: everyone else on the conversation's latest
received email (its To and Cc), without the deployment's own mailboxes and the reply's recipient, each address once.
The composer prefills the row with it; the planner removes people (×) or adds addresses, and the edited list is kept in
the conversation's draft. The optional `cc` command field (at most 20 entries, each exactly one valid address, no line
breaks; `422` otherwise or on other channels) is saved on the outgoing message as `copyTo`, so delivery and retries copy
the same people, on the existing thread or a new one. Omitting it copies nobody: the suggestion is never applied
server-side. Messages keep an email's recipients (`ConversationMessage.sentTo`/`copyTo`, comma-joined RFC 5322 entries):
Gmail fills both from received mail and from replies imported from the Sent label; a reply sent from the CRM stores
its recipient and Cc. The chat shows them under the author ("to Sarah, me · cc Marco, Giulia", long lists ending in
"+N", the full lists as the tooltip) only when the email had a Cc or several To.

Files can be selected with the paperclip or dropped anywhere in the active conversation pane. The drop target respects reply permissions, channel attachment support, file count and upload size limits. Drops add files to the draft tray; they never send the reply automatically. Internal-note mode does not accept attachment drops.

An outbound reply may carry files. They are stored as references, never as bytes on the message:
the composer uploads each file through `POST /api/media` and sends back the returned URLs, which
`ConversationMessage.attachments` keeps newline-joined — the same shape an `images` field input
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
| `GET /workspace` | `{workspace:{version,config:{actions,folders},fields},customerCatalog}`; `fields` are the bound `customer.<key>` fields in order |
| `GET /statuses` | Configured `{id,description,color,closed}` choices |
| `GET /contacts/{id}` | `{catalogName,fields,identities,conversations,canWrite}`; conversations are scoped |
| `GET /inbox-workspaces[?conversation=<id>]` | Permitted `{key,label,canWrite}` workspaces; with `conversation`, those holding it (forbidden when none) |
| `GET /inbox-workspaces/{key}` | `{key,label,canWrite,config,list,channelAccounts,rows,total,hasMore,nextCursor}`: authorized rows (filters, `sort`/`dir`, cursor/limit) and the conversation list's default descriptor |
| `GET /inbox-workspaces/{key}/conversation/{id}` | Authorized conversations for the same customer |
| `GET/POST /inbox-workspaces/{key}/conversations/{id}/comments` | Internal notes. POST validates `body` against the shared `onno.comments.max-length` (4000 characters by default) and the CRM request cap of 8000 characters. Invalid text returns `422`. |
| `GET/POST /conversations/{id}/messages` | History / enqueue `{body,attachments,subject?,recipient?,from?,html?,cc?}` (`cc`: who an email reply copies, see above) (`html`: an email's formatted version of `body`, sanitized to formatting tags into the message's `bodyHtml` and sent as its HTML part; `400` on other channels); each message returns `attachments:[{url,filename,contentType,size,image}]` and the stored `subject`/`recipient`/`senderAccount` and the email's `sentTo`/`copyTo` (null when not set), so history shows overrides. A `from` the delivery connection does not offer is `400` |
| `GET /conversations/{id}/delivery` | `{connected,label,maxTextLength,replyCapability,replyReason,maxAttachments,attachmentReason,recipient,recipients,senders,sender}`; `recipient` is the default address and `recipients` the choices (null/empty when the transport offers no selection); `senders` (`[{id,label}]`) are the accounts the reply may come from and `sender` the default id (empty/null when there is no choice), as offered to the caller; `cc` the suggested Cc (reply-all; empty when none). Addresses known only through another user's personal account are left out of `recipients`; `cc` comes from mail the conversation already shows |
| `POST /conversations/{id}/status` | `{status}` (a configured status id) → the updated conversation state; a missing body or `status` is `400` |
| `POST /conversations/{id}/messages/{messageId}/retry` | Retry a failed reply |
| `POST /conversations/{id}/read` | Acknowledge unread messages |
| `GET/POST /contacts/{id}/activity` | Scoped timeline / record an internal activity; message entries carry the same `attachments` shape, `senderAccount`, `sentTo` and `copyTo` |
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

### Building your own screens

`@onno/crm` is built in three layers, and an app can stop at any of them:

1. **Screens**: `CrmInbox`; `CrmConversation` (one chat anywhere: a record page, a column, a
   panel); `openCrmConversation({ workspace, conversation, title })`, which opens one in a docked
   window that stays open across pages, like a mail compose box; `CrmContactPage` (profile, the
   contact's conversations, and the chat); and `CrmConnections`.
2. **Parts**:
   - the list: `ConversationRow`, `ConversationMenu`, `ConversationList` / `ConversationRowContent`;
   - the timeline: `ChatTimeline`, `TimelineMessage`, `TimelineComment`, `TimelineSystemEvent`,
     `MessageAttachments`, `CrmAvatar`;
   - the composer: `CrmComposer`, `CrmComposerFields`, `SenderControls`, `AttachButton`,
     `FileDropOverlay`;
   - the contact: `CrmContactPanel`;
   - `CrmConversationBody` (header + timeline + composer, for state the app already holds).
3. **Hooks**:
   - `useCrmConversation({ conversation, workspace })`: the contact's timeline across channels,
     delivery, the conversation's commands, `execute`, `reload` and `loadEarlier`, all live;
   - `useCrmComposer(conversation, config)`: the per-conversation draft, files, sender, `submit`,
     `retry` and `insertDraft`;
   - `useCrmWorkspaceConversation(workspace, id)`: one conversation, its siblings and its config;
   - `useCrmContact(id)`.

Each part takes the hook state as props, so an app replaces one piece and keeps the rest.
`CrmComposer`'s `renderReplyInput` replaces only the reply editor (a rich-text field, say) in both the
inline composer and its larger window; it gets `CrmReplyInputProps` (`value`, `html`, `formatting`, `onChange`,
`onSubmit`, `disabled`, `maxLength`, `label`, `placeholder`, `describedBy`, `surface`), while drafts, files, sending
and the other fields stay the composer's. When `formatting` is true (email), the field may report
`onChange(text, html)`: the HTML is kept in the draft beside the text and sent as the email's HTML part, the text as
its plain part; `onChange(text)` alone, or any other change of the text, makes the reply plain again. Mark the editable element `data-composer-reply` so the
composer can put the caret back in it; Ctrl/Cmd+Enter is ignored while an IME is composing.
Two more hooks reshape the composer without redrawing it. `renderAttach` replaces the paperclip wherever the
composer draws it (the toolbar, the mobile actions panel, the larger window) with the app's own control, a menu of
files and templates say; it gets `CrmAttachProps` (`composer`, `chooseFiles` to open the composer's file picker,
`surface`, `labelled`). `renderFields` arranges the fields above the reply: it gets `CrmComposerFieldParts`, onno's
own `header` (sender row with the larger-window button), `sender`, `recipient`, `recipientNotice`, `cc` (the Cc row,
`CcField`) and `subject` (null where the channel has none), `expand` to open the larger window, a `summary` of what the
rows hold (`summary.cc` lists the people copied, for a "+2 in Cc") and whether the planner `changed` the recipient, Cc
or subject, so an app can fold them behind one line as Gmail does and still let them out.
`insertDraft(text, files, html)` takes the text's formatting too (a letter template): an email reply keeps it, joined
to the draft's own HTML, and sends it; other channels send the text.
`CrmInbox`'s `components` swaps a part in place. Every replacement gets the same props the built-in
part does: `ConversationRowProps`, `CrmComposerProps`, `InboxContactPanelProps`.

```tsx
// The customer's orders inside the chat, at the date they were placed.
const orderCards: CrmTimelineSource[] = [{ id: "orders", useEntries: ({ customerId }) => {
  const { data = [] } = useRecords("Orders", { where: { customer: customerId }, limit: 5 });
  return data.map(order => ({ id: String(order.id), at: String(order.date), type: "custom",
    render: () => <OrderCard order={order} /> }));
} }];

<CrmInbox workspace="support" timelineSources={orderCards}
  components={{ ContactPanel: CustomerSidebar, ConversationRow: props => <ConversationRow {...props} appearance={{ previewLines: 2 }} /> }}
  renderEntry={(entry, render) => entry.type === "message" && isUrgent(entry.message) ? <Highlight>{render()}</Highlight> : render()} />

<CrmContactPage contact={customerId} workspace="support" timelineSources={orderCards} />
openCrmConversation({ workspace: "support", conversation: id, title: "Anna Petrova" });
```

A timeline source's `useEntries` is a hook: it may use the app's data hooks. Keep the sources array
stable (module-level), so the hooks run in the same order on every render. Custom message bodies
remain `messageRenderers` (`{ id, priority?, matches, component }`). A renderer that throws falls
back to the default drawing.

The row menu (right-click or ⋯) lists the conversation's `Commands<Conversation>` that need no
input. Commands with a `.group("Change status")` appear under that submenu. A command that asks for
input stays with the conversation's own actions, through `crm.chat.header` contributions and
`context.execute`. The actions endpoint returns `group` and `inputs: boolean` for this.
`rowMenu={{ open: false }}` drops "Open conversation"; `rowMenu={{ groups: false }}` drops the
chat-group items. A command's icon, and `"hidden"` to leave it out of the menu, come from the app's
customization of the conversation entity, as for any entity's list menu:
`entities: { CrmConversations: { commands: { "assign-to-me": { icon: UserCheck }, "state-closed": { icon: CircleCheck } } } }`.
Commands without an icon show none. Offer a command only in some states with `availableWhen`: the
menu lists only the ones the actions endpoint marks visible.

## UI extensions and verification

The inbox exposes these outlets:
- `crm.chat.header`, `crm.chat.composer` and `crm.chat.aside`;
- `crm.chat.context-menu`, which gets the conversation's `actions` and `execute`;
- `crm.chat.message.actions`: beside each message, with the message as `record`, for example to
  quote, forward or create a task from it;
- `crm.chat.timeline.start`: pinned above the history, for example an order summary;
- `crm.contact.profile` (directly under the profile card) and `crm.contact.actions`;
- `crm.contact.page.aside`, on `CrmContactPage`;
- `crm.inbox.toolbar`. Contribute to them in the app's
`defineApp({ extensions: [{ id, slot, component }] })`; contact contributions receive the bound
catalog name and host permissions. Server authorization is independent of what is shown. Message
renderers are the inbox's `messageRenderers` prop (`{ id, priority?, matches, component }`).

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

CRM installs no Channels page. The host can render `<CrmConnections />` on any page. It shows
contributed connection descriptors; each provider's setup form is a `CrmConnectionSetup`
(`{ providers, component }`) passed as `setups`, receiving the account, `canManage` and `refresh()`.
`@onno/crm-channels` exports the bundled channels' as `channelSetups`. Third-party connectors pass
their own; the CRM frontend needs no changes.

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
Offer a status filter with `{ field: "status", label: "Status", type: "multiOptions" }` in the
inbox's `list.filters`; its choices come from the binding.
Catalog choices are read live, and deleted choices cannot be selected. With no binding there is
no status selector, filter or table column and no transition commands. There is no CRM status editor.

Reply capability is independent of connectivity. The delivery response carries `connected`,
`replyCapability` (`AVAILABLE`, `READ_ONLY`, `WINDOW_CLOSED`) and `replyReason`.
For example, `new Connection(true, "Archive", 8000, ReplyCapability.READ_ONLY,
"This archive accepts incoming messages only.")` represents a healthy read-only channel.
A disconnected provider reports `connected=false`; workspace reply permission is checked separately.
The composer distinguishes these states, and reply/retry commands enforce `canSend()` on the server.

Priority is optional: bind a host catalog/enum with `CrmPriorityBinding.catalog(...)` or
`.enumeration(...)`; a `priority` filter in the inbox's `list.filters` takes its choices from it. No priority enum, default or
implicit column/filter is installed. The priority UUID is scoped to that host source.
Assignment, close/reopen, status-change and manual identity-linking HTTP actions are not bundled.
Host `Commands<Conversation>` beans declare ordinary record commands (`c.command(key)…run(...)`);
extension buttons receive their descriptors and call `context.execute(key, inputs)`. The scoped API is
`/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions` (GET `[{id, title, visible,
enabled, group, inputs}]`, POST `/{key}` with `{inputs:{...}}`). It checks workspace write access, application
read-only mode, command roles and the commands' `availableWhen`/`enabledWhen` rules. CRM adds no business-field mutations or automatic history
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

Custom list views receive optional `ListRendererProps.total`, the server count matching the
current search and filters, independently of how many pages are loaded. A null/omitted total means
unknown. The inbox uses this count in its main chat header; folder counts remain scoped to loaded
rows and are labeled as the current view.

## Unified inbox controls and manual activity

Enable the packaged channel bar with `toolbar={{ channels: true }}` on `<CrmInbox>`.
Channels remain visible; changing channel resets the account selection. Set `accounts: false` to
hide the account picker while keeping the channel bar. Channel labels are `channelLabels`. The account picker is
restricted to accounts with readable conversations in the workspace, before search, filters and
pagination. These are conversation accounts, not a claim that a connector is online. The response's
`list.channelAccounts` contains `{id, channel, label}` choices. Empty connected accounts still belong
in connector settings until the host gives them explicit workspace visibility.

### Inbox toolbar and folders

The inbox offers **Folders / Everything** where its workspace has folders: a reading preference,
not a filter — "Everything" drops the grouping without dropping any chat. `grouping={false}` hides
it where the folders carry the workspace's meaning, such as a pipeline whose folders are its stages.
The header is also the `crm.inbox.toolbar` outlet: an app's contribution receives
`record.folders` and `context.view`, the same string map the switch writes (`view.grouping`).

```tsx
const unassigned: UiExtension = {
  id: "acme.inbox.unassigned", slot: "crm.inbox.toolbar",
  component: ({ context }) => (
    <Button size="toolbar" aria-pressed={context.view?.get("owner") === "none"}
      onClick={() => context.view?.set("owner", context.view.get("owner") === "none" ? undefined : "none")}>
      Unassigned
    </Button>
  ),
};
defineApp({ extensions: [unassigned] });
```

A folder draws the plain folder glyph unless the app gives it one, by folder key:

```tsx
import { PartyPopper } from "@onno/ui/icons";
<CrmInbox workspace="sales" folders={{ booked: { icon: PartyPopper, color: "#059669", label: "Booked" } }} />
```

Deliberately explicit rather than inferred from the label's wording: an inferred identity stops
working the moment that label is written in another language.

The controls use `EntityListView.queryParams` for additional server-validated query constraints,
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

Pass `inboxRoute="/inbox"` to `CrmContactDetails` to expose **Open conversation**. It opens the authored inbox with `?conversation={id}`; the server verifies
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

Drafts belong to the conversation (`src/main/ui/composerDrafts.ts`): the reply, an edited subject, the
pinned recipient, the edited Cc (`cc`, null while it follows the suggestion), the chosen sending mailbox and reply-or-note mode survive switching chats,
contacts, inbox modes and tabs. The text parts are kept in `localStorage` under
`onno.crm.draft.v1|<workspace>|<conversation>`, best effort (guarded access, 64 000 characters per
entry, at most 100 entries, dropped after 30 days); files already uploaded for the next reply stay
in memory only. A draft is cleared after a successful send or when its author empties it. Clicking
a contact opens the conversation that holds its unsent draft, and the page asks before unloading
while text or files written in this session are unsent. `insertDraft` contributions append to the
open conversation's draft as before; `insertDraft(text, files)` also puts stored files in its tray.

The inline reply grows with its text up to `min(240px, 40vh)`. **Open in a larger window** edits the
same draft in a non-modal `@onno/ui` window (`FloatingWindow`), docked in the bottom-right corner
like a mail compose box. The inbox behind it stays usable: switch chats, open the contact, scroll the
history, copy dates and numbers. The window can be minimized to its title bar, dragged out into a
freely floating and resizable window, or maximized. Its state, position and size are remembered in
the browser (`persist="crm.composer"`). On phones (under 640px) it always fills the screen. Closing it
returns focus to the inline field.

In the inbox the window belongs to its conversation, like a mail draft (`src/main/ui/composerWindows.ts`):
choosing another chat leaves it open, docked or minimized where it was, still writing to and sending
from the chat it was opened for, while the newly chosen chat gets its own inline composer. Each chat
has at most one window (`crm-composer:<draft key>`), so several drafts can sit side by side; choosing a
chat whose window is open hands that same window back to the chat's composer. The inbox draws the
windows of chats it is not showing with its `Composer` component and `windowOnly`, so an app's own
composer must pass its props through to `CrmComposer`. Outside an inbox (`CrmConversation`, an app's
screen) a composer's window is its own and closes with it.

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
restored mail archived in Gmail. All mailbox paths live in `src/main/ui/mailboxApi.ts`
(`MAILBOX_ENDPOINTS`).

### The inbox UI (`@onno/crm`)

`<CrmInbox>` takes typed props; every one is optional but the workspace.

| Prop | What it sets |
| --- | --- |
| `workspace`, `modes`, `conversation` | The workspace bean by key; other workspaces offered as a switch (`[{ workspace, icon }]`); a conversation to open |
| `list` | The conversation list, as an entity list customization: `columns`, `labels`, `sort`, `filters`, `groupable`, `view: "inbox" \| "table"`, `inboxLabel` |
| `toolbar` | `placement: "renderer"` (controls inside the conversation pane), `filterSidebar`, `mobile: "sheet"`, `controls`, `channels`, `accounts`, `views`, `filters`, `actions`, `title`, `count`, `viewSwitcher`, `search` |
| `layouts` | Toolbar regions (`start`, `center`, `end`, `secondary`, `overflow`, `hidden`) per pane: `list`, `mobileList`, `chat`, `narrowChat`, `mobileChat`, `composer`, `mobileComposer` |
| `row` | `showAccount`, `showChannelBadge`, `previewLines`, `badges: [{ field, equals, label }]` |
| `rowTitle`, `rowPreview` | The conversation fields a row's title and preview show (default the customer, the last message) |
| `contactFields` | The contact panel's fields in order: `"customer.email"` or `{ key, label, section, format }` |
| `actionLabels`, `folders` | Labels of the workspace's actions; glyph, hue and label per folder key |
| `showAvatars`, `showIdentities`, `showEmptyFields`, `showSystemEvents`, `showTimestamps`, `showDeliveryStatus` | Visibility |
| `grouping`, `connectionsRoute`, `channelLabels`, `messageRenderers` | See above |
| `components` | Parts the app draws itself: `ConversationRow`, `Composer`, `ContactPanel`, `EmptyChat` (see [Building your own screens](#building-your-own-screens)) |
| `timelineSources` | The app's own entries in the chat's timeline (orders, bookings, …) |
| `renderEntry` | Draws, wraps or drops any single timeline entry |
| `rowMenu` | `{ open?, groups? }`: the row menu's built-in items |

Chat header item ids: `back` (compact only), `avatar` (desktop only), `title`, `contact`,
`openContact`, `scheduleCall`, `logActivity`, `extensions`, `actions`; composer item ids: `mode`,
`attach`, `send`, `extensions`, `tools`, `channel`; list header ids: `title`, `count`, `controls`.
A configured layout replaces the default one for that pane; `hidden` wins and unknown ids are
ignored. Layout and visibility never grant access to unavailable commands.

```tsx
<CrmInbox workspace="support"
  list={{ columns: ["customer", "subject", "lastMessageAt"], sort: "-lastMessageAt" }}
  toolbar={{ placement: "renderer", controls: ["search", "filters"], channels: true }}
  layouts={{ chat: { start: ["back"], center: ["title"], end: ["contact"], overflow: ["openContact", "logActivity", "extensions"] } }}
  contactFields={["customer.email", { key: "customer.stage", label: "Stage", format: "badge" }]} />
```

A conversation's record route opens in the workspace holding it when the app adds
`{ path: "/catalogs/crm_conversations/:id", component: CrmConversationPage, view: false }` to its pages.

With `toolbar.filterSidebar` and `placement: "renderer"`, a persistent navigation column appears
to the left of the inbox when its container is at least 1100px wide; narrower containers use the
shared filter sheet. `connectionsRoute="/connections"` adds a Manage connections footer.

Messages are acknowledged once their history is loaded and the conversation is visible; contact
metadata and transport availability load independently. Successful acknowledgements clear the
local unread badge immediately while the shared feed catches up. Failed acknowledgements preserve
unread state. A newer server row supersedes the local read snapshot.

The attachment action remains visible in reply mode. When unavailable, the button is disabled
and its wrapper exposes the channel/permission/file-limit reason on hover. It opens the file picker only when attachments are allowed.

## Scoped connection accounts

Hosts still author the route and navigation. `<CrmConnections>` presents shared accounts,
personal accounts, provider availability, loading skeletons, retries, and account management:

```tsx
<CrmConnections setups={[channelSetups]} scopes={["shared", "personal"]} defaultScope="shared"
  sharedLabel="Team accounts" personalLabel="My accounts"
  providers={["gmail", "telegram", "instagram", "whatsapp"]} showUnavailable />
```

All props are optional; `providers` is a display allowlist (omitted: all installed providers) and
`scopes` the scope switcher and its order. These are presentation, not access rules.

`CrmAccountProvider` beans contribute provider descriptors even when disabled: branding, description,
supported scopes, availability, and setup guidance. Their account views and commands remain
connector-owned. A `CrmConnectionSetup` component receives the account (`accountId` for managed
accounts, the provider key, scope), a server-derived `canManage`, and `refresh()`. Legacy
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

- **Shared accounts** are managed through `CrmChannelAccess.canManage` (explicit `crm.administer` holders by default; never implied
by an application without `RoleGrants`).
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
`CrmInboxes`), exports, and generic entity events are open only to explicit `crm.administer`
holders that also hold an entity grant on them. With personal accounts enabled they are closed to
those as well, because their contracts have no
per-record principal filter; use the scoped CRM APIs. This is configuration, not data, so it does
not change at runtime when someone connects an account. Every `EntityAccessPolicy` constrains
every role, so such privacy boundaries need no opt-in.

Contacts are not private. A correspondent who writes to a personal inbox is resolved into the host's
shared contact catalog under the host's own permissions, so colleagues may see that the contact
exists, but not the personal conversation or its channel identity.

Bundled Gmail and Telegram business support shared and personal accounts. The other bundled
adapters run on one shared, server-configured account and advertise that limitation instead of
offering nonfunctional personal setup. Provider capability claims must match their actual transport
and credential isolation.

### Sidebar filter content and pipeline stages

The sidebar is a presentation of the inbox's filters, not a fixed CRM funnel.
`toolbar={{ filters: ["status", "pipelineStage"] }}` chooses the visible groups and their order;
`filters: ["pipelineStage"]` shows only the pipeline, and `filters: []` hides all filter groups.
`channels`/`accounts` independently select channel/account navigation; `filterSidebar: false`
switches off the desktop navigation card. A facet's label and options come from its declaration. Option colours are rendered in both the sidebar and sheet.
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
with any `Conversation` field name (inherited catalog fields such
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
