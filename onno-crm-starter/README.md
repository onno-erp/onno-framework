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

Use the full constructor for separate read/write roles and a workspace-specific configuration
function. Workspace roles are application roles; there is no required `CRM_AGENT` or `CRM_MANAGER`
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
- **Personal chat groups:** register `new CrmFeatures(true)`. Disabled by default, with no group
  tables created and no group controls shown. Group membership never grants conversation access.
- **Connections:** embed the `crmSettings` widget wherever wanted. Only enabled connectors appear.
  `CrmChannelAccess` controls credential management and defaults to administrators only.
- **Sales:** the optional `example/src/sales/java` recipe is a catalog, stage enum, ordinary
  `EntityView` and three standard metric/count widgets. Build it with `-PsalesExample`; it is not
  part of the CRM artifact or the default inbox example.

`CrmWorkspaceCustomizer` configures presentation only. List selection is a framework primitive:
`ListSpec.selectionCheckboxes(true)` enables checkboxes on any ordinary catalog/document table.
Selected records use the existing top-toolbar and context-menu batch commands; select-all means
loaded rows, including expanded groups. CRM does not install a second bulk-action backend.

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
`CrmMessageTransport.connection(conversation)` reports connectivity, label and maximum text length.
When unavailable, the label explains why replies are blocked. The composer disables replies while
checking, sending or unavailable, preserves drafts, and offers a connection recheck. Internal notes
remain independent of channel connectivity.
`enqueue(conversation, message)` runs in the same transaction as the outgoing message. Connectors
write a durable outbox and deliver after commit. `CrmMessageRouter` rejects multiple providers
claiming one conversation. Retries apply only to failed outbound replies in that conversation.

`onno-crm-channels-starter` supplies opt-in Telegram, Gmail, Instagram and WhatsApp adapters. It also
requires a host customer binding. Provider routing, deduplication, checkpoints and credential storage
remain connector-owned; all new-contact creation goes through the host inbound policy.

## Commands and read contracts

All browser commands use normal session authentication and CSRF. Each conversation read/command
requires `?workspace=<key>` and current membership; writes additionally require workspace write roles.

| Route under `/api/crm` | Behavior |
| --- | --- |
| `GET /workspace` | `{workspace:{version,config,availableFields},canConfigure:false,customerCatalog}` |
| `GET /statuses` | Configured `{id,description,color,closed}` choices |
| `GET /contacts/{id}` | `{catalogName,fields,identities,conversations,canWrite}`; conversations are scoped |
| `GET /inbox-workspaces` | Permitted `{key,label,canWrite}` workspaces |
| `GET /inbox-workspaces/{key}` | Authorized rows with filters, cursor/limit, totals, config and permissions |
| `GET /inbox-workspaces/{key}/conversation/{id}` | Authorized conversations for the same customer |
| `GET/POST /inbox-workspaces/{key}/conversations/{id}/comments` | Internal notes |
| `GET/POST /conversations/{id}/messages` | History / enqueue `{body}` |
| `GET /conversations/{id}/delivery` | `{connected,label,maxTextLength}` |
| `POST /conversations/{id}/messages/{messageId}/retry` | Retry a failed reply |
| `POST /conversations/{id}/read` | Acknowledge unread messages |
| `GET/POST /contacts/{id}/activity` | Scoped timeline / record an internal activity |
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
`crm.chat.context-menu` and `crm.contact.actions`. Contact extensions receive the bound catalog name
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

CRM installs no Channels page. The host can embed `crmSettings` on any page. This optional widget
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
