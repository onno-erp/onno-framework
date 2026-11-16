# Extending onno-framework

How to build, publish, and list a community extension. The framework is designed to be extended
**without forking**: you ship a separate artifact that the host application opts into. This guide is
the public companion to the architecture reference in [ARCHITECTURE.md](ARCHITECTURE.md) and the
modeling playbook in [AGENTS.md](../AGENTS.md).

> Built something? Jump to [Get your extension listed](#get-your-extension-listed).

## The six extension surfaces

Pick the one that matches what you're adding. Most "integrations" are **connectors**.

| Surface | You're adding… | Mechanism |
| --- | --- | --- |
| **Connector** | a binding to an external system (a PMS, a bank, a marketplace, an ERP) | a Spring Boot auto-configuration starter that exposes a typed client + sync service |
| **SPI** | a pluggable implementation of a framework contract | a `@Bean` implementing an SPI interface (`MediaStorage`, `MailDispatcher`, `MailEventVerifier`, an additive `AuthMethodsContributor` login button, a read-only record-menu `RecordActionContributor` or list-selection `SelectionDownloadContributor`, a `PdfConverter` for print forms, a custom `SecurityFilterChain`/`UserDetailsService`, a Kafka `EventHandler`) |
| **UI** | a screen, component, outlet contribution or record command | an npm package of React components on `@onno/ui` (rendered by the host app's pages, or passed to `defineApp({ extensions })`), plus `Commands`/`Checks`/`PickerOptions` beans for behaviour (see [onno-ui/README.md](../onno-ui/README.md)) |
| **Observability** | a semantic business outcome or timed operation | inject `TelemetryRecorder`; keep names and dimensions low-cardinality and free of customer data (see [onno-observability-starter/README.md](../onno-observability-starter/README.md)) |
| **MCP** | an agent-callable application or integration operation | `@McpTool` on a Spring bean method, or an `McpToolProvider` bean for direct SDK access (see [onno-mcp-starter/README.md](../onno-mcp-starter/README.md#custom-tools)) |
| **Skill / plugin** | guidance that makes an AI agent good at your domain | a Claude skill published through a plugin marketplace (see [.claude-plugin/marketplace.json](../.claude-plugin/marketplace.json)) |

A connector and an SPI both ship as a starter; the difference is whether you wrap an outside system
or satisfy a framework contract. The rest of this guide focuses on the **starter** shape since it
covers both.

UI extensions are npm packages of React components that import `@onno/ui` — the way
`onno-print-starter` ships `@onno/print` and `onno-crm-starter` ships `@onno/crm`, each in
`src/main/ui` beside the starter's Java code. Declare `@onno/ui` and `react` as peer dependencies;
the host app adds your package to its `package.json` and renders your components on its pages
(`<PrintTemplates />`), in `recordPanels`, or as `defineApp({ extensions })` contributions. Read data
through `@onno/ui`'s hooks (`useRecords`, `useAggregate`, `useCommand`, …), which refresh themselves
from the host's single authenticated SSE stream; use `useUiEvents` for other events. Never create
your own `EventSource`, which bypasses cross-tab sharing and session recovery. Use the host's
controls (`Button`, `Select`, `toast`, `DialogShell`, …) rather than lookalikes. A third-party
browser library is an ordinary dependency of your package; you are responsible for its license,
security, browser support and bundle size. Behaviour stays on the server: a command your screen
offers is a `Commands` bean, enforced on every surface.

The CRM module owns messaging infrastructure and its `@onno/crm` components. An explicit `CrmCustomerBinding`
connects it to a host catalog; optional `CrmAgentBinding` connects assignment to the host identity
model. The host owns business fields, lifecycle stages, sales models, pages and navigation. Provider
adapters call the host inbound-contact policy, not a module-owned customer repository. See the
[CRM composition contract](../onno-crm-starter/README.md).

UI extensions navigate with `openRecord("Sales Orders", id)` or `openRoute(links.record("Sales
Orders", id))` from `@onno/ui` (or through an extension context's `openRecord`); the typed `links`
take the logical name and build the `onno://` URL. A raw `onno://` URL or a notification `link` uses
the snake_case route name, not the logical name REST uses: `@Document(name = "SalesOrders")` is
`onno://documents/sales_orders/{id}`, while REST is `/api/documents/SalesOrders`. A link with the
REST spelling still renders, but live updates and the localized tab title don't apply to it (#247).

## Key idea: a connector wraps an external system, it does not model the business

A connector defines **zero** framework metadata — no `@Catalog`/`@Document`/registers/posting/UI.
The catalogs, documents, registers, posting, and UI live in the **consuming application**. A reusable
connector should not depend on application model types. Host-side adapter code may translate the
application's `Ref<T>` values and enums into the connector's external ids and codes. A connector is
a Spring Boot **auto-configuration starter that exposes a typed client + service for one external
system**; the host app owns the domain mapping.

This keeps the seam clean: your integration is reusable across any app built on the framework, and
the app owns its own model.

## The starter shape

```
onno-<name>-starter/
  <Name>Properties.java          @ConfigurationProperties(prefix = "onno.<name>")
  <Name>Client.java              the typed client interface
  Default<Name>Client.java       typed HTTP/SDK client implementation
  <Name>Service.java             convenience facade (pagination, polling, mapping)
  Onno<Name>AutoConfiguration.java   @AutoConfiguration, beans @ConditionalOnMissingBean
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports   # lists your @AutoConfiguration class
  README.md
```

The auto-configuration:

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "onno.shopify", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ShopifyProperties.class)
public class OnnoShopifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RestClient.class)
    public ShopifyClient shopifyClient(ShopifyProperties props) {
        return new DefaultShopifyClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ShopifyService shopifyService(ShopifyClient client) {
        return new ShopifyService(client);
    }
}
```

- Gate the whole starter with `@ConditionalOnProperty(prefix = "onno.<name>", name = "enabled")`.
- Make public extension seams (client, SPI, and facade beans) replaceable with
  `@ConditionalOnMissingBean`. Keep dependent bean conditions coherent so replacing a client does
  not accidentally create two competing service graphs.
- List the auto-configuration class in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  (Spring Boot 3's mechanism — one fully-qualified class name per line). Adding the dependency is then
  enough; no `@Import` needed in the host.
- Build with `java-library` + `withSourcesJar()` / `withJavadocJar()`. Depend on the published core:
  `su.onno:onno-framework` (`api`) and, if you need auto-config helpers,
  `su.onno:onno-framework-starter` (`implementation`).

Study a real starter in this repo for the full shape —
[`onno-cluster-starter/`](../onno-cluster-starter) is a self-contained example (properties,
conditional beans, a pluggable event-bus SPI, and an auto-configuration imports file).

## Conventions that keep the ecosystem clean

These are the rules that let many independent extensions coexist. Follow them so installs don't
collide and config stays predictable.

- **Use your own Maven group.** `su.onno` (official) and `su.onno.enterprise`
  (commercial) are **reserved** — do not publish under them. Use your own, e.g.
  `io.github.<you>` or `com.<yourcompany>`.
- **Use your own Java package.** The `su.onno.*` package space is **reserved** for the framework.
  Put your code under your own package (e.g. `com.acme.onno.shopify`).
- **Name the artifact `onno-<name>-starter`.** It signals an onno extension and sorts well.
- **Namespace config as `onno.<name>.*`** and include an `enabled` flag (default off is the safe
  choice for a starter that needs credentials). Bind it with `@ConfigurationProperties` and document
  every property in your README.
- **Declare the onno-framework version you support.** State the version (or range) you build and test
  against in your README and in your registry entry — the published surface is the
  `su.onno:*` artifacts on Maven Central.
- **Tag your repo** `onno-framework` and `onno-extension` on GitHub so others can find it.
- **Reserved plugin id:** the Gradle plugin id `su.onno.desktop` is the framework's; don't reuse it.

## Reuse these domain seams (don't reinvent them)

When your extension reacts to the host's business data, use the framework's seams — they're covered
in depth in [ARCHITECTURE.md](ARCHITECTURE.md):

- **`Ref<T>` + `RefResolver`** are the canonical bridge in the host adapter between app domain objects and your code.
  `refResolver.resolve(ref).orElse(null)` dereferences a `Ref<Customer>` into the catalog entity;
  map enums to the external system's codes with a `switch`.
- **React to a posted document with a Spring `@EventListener` on `DocumentPostedEvent`** (full
  dependency injection), not from inside `handlePosting`. Guard external calls so a failure logs but
  doesn't block the host's save/post.
- **Posting runs in its own transaction.** If your host-side glue saves a document and then posts it,
  let the save commit first — don't wrap save+post in one `@Transactional` (it silently leaves the
  document unposted).
- **Async external workflows are usually submit-then-reconcile.** If the external system doesn't push
  webhooks, model a submit call plus a scheduled reconcile job, and keep an idempotency ledger
  (a starter may own its own `onno_`-prefixed table).
- **Serve users inside their record scope.** A connector endpoint, command handler or
  `@McpTool` that returns or changes host records on a user's behalf takes the caller's
  `AccessSubject` (controller parameter, `CommandContext.subject()`, `McpToolContext.subject()`) and
  either reads through the web starter's query services or checks with `RecordAccess`
  (`require`/`can`/`filter`/`clause`). Typed repositories are unscoped trusted code. Contribute
  subject attributes for host policies with an `AccessSubjectContributor` bean. See
  [Record access policies](RECORD_ACCESS_POLICIES.md).
- **Expose a focused agent operation with `@McpTool`.** Put it on a public Spring bean method,
  describe inputs with `@McpToolParam`, restrict roles where needed, and mark writes with
  `readOnly = false` so the global MCP write gate applies. Use `McpToolProvider` only when direct
  MCP Java SDK control is required.

## Definition of done

Before you publish and ask to be listed:

- [ ] Builds against a supported onno-framework version (state which one).
- [ ] Has a README: what it does, the `onno.<name>.*` properties, a minimal setup snippet.
- [ ] Has a declared license (an SPDX id in the repo).
- [ ] Uses your own Maven group and Java package (not `su.onno` / `su.onno.*`).
- [ ] For a starter: gated by `onno.<name>.enabled`, public client/SPI/facade seams are replaceable,
      and the module has a valid `AutoConfiguration.imports` file.
- [ ] `./gradlew publishToMavenLocal` produces a consumable artifact **with sources, javadoc, and a
      POM** — a build can pass yet still fail to produce these.

## Get your extension listed

The community catalog is [INTEGRATIONS.md](../INTEGRATIONS.md), generated from a machine-readable
registry. To get listed:

1. Add an entry to [`community/registry.json`](../community/registry.json). Its schema is documented
   by [`community/registry.schema.json`](../community/registry.schema.json), and Gradle performs the
   repository's structural and duplicate checks.
2. Run `./gradlew checkCommunityRegistry generateIntegrationsDoc`.
3. Open a PR with both files. See the
   [listing criteria](../CONTRIBUTING.md#listing-a-community-integration) in `CONTRIBUTING.md`, or
   open a [community integration submission](https://github.com/onno-erp/onno-framework/issues/new?template=integration-submission.yml)
   issue and a maintainer will help.

Listed projects are maintained by their authors and are not endorsed by the onno-framework team.


### CRM channel delivery

A CRM host can supply `CrmMessageTransport` to expose a channel connection and enqueue a reply
inside the CRM save transaction. Keep provider clients independent of CRM entities and put contact
mapping, checkpoints, and outbox ownership in the host adapter. The default transport disables
external replies until connected. See [the CRM contract](../onno-crm-starter/README.md#message-delivery-contract)
and [the channel starter](../onno-crm-channels-starter/README.md).

CRM hosts may register ordered `CrmWorkspaceCustomizer` beans for workspace configuration; customer fields stay in ordinary host catalogs. `CrmChannelConnection` is the connector-owned status/setup SPI behind `<CrmConnections />`.
Its public view must never include credentials, and commands must validate provider identity before
changing routing or persisted secrets. The host `CrmChannelAccess` policy gates commands; connectors own secret storage
and OAuth/token verification. See `onno-crm-starter/README.md` for the current contract.

Components report outcomes with `toast.success(message)` (or `error`, `info`, `warning`) from
`@onno/ui`, the shell's one notification stack.

`CrmInboxWorkspace` beans define application-owned team inbox views, with typed conversation
selection, explicit read/write roles and independent `CrmWorkspaceService.Config` customization.
They can combine multiple channel accounts without changing transport destinations. See the CRM
README for scoped endpoint and shared-contact boundaries.

For generic entity surfaces, `EntityAccessPolicy` supplies an additional deny-only gate to
`AccessService`. Policies cannot grant rights absent from annotation RBAC and ADMIN bypasses the
additional gate. Keep policy beans lightweight and avoid circular dependencies on `AccessService`.

`OptionsFacet` is exported from `@onno/ui`: the same filter chip used by entity lists.
Pass `label`, `options` (`value`, `label`, optional `color`/`avatarUrl`), `multi`, `selected: string[]`,
and `onChange`. Empty selection means no constraint; the consuming component owns data filtering.

`EntityListView` is also exported for scoped operational lists. Supply the
host list descriptor (`list`, including a scoped `feed`), optional `renderer` component for the
custom body, and optional numeric `refreshKey` to trigger a soft live refresh. The host retains
ownership of the entire standard list header, filtering, sorting and table/custom-view switching.

### Packaged CRM channel adapters

`onno-crm-channels-starter` is an optional Apache-2.0 module depending on `onno-crm-starter`. It supplies provider clients plus CRM-specific mapping/transport adapters through auto-configuration, with all providers disabled by default. Adopters configure credentials and workspace predicates instead of copying example classes. See [the module guide](../onno-crm-channels-starter/README.md). Its current single-account/single-worker constraints are explicit; third-party connectors can still implement `CrmMessageTransport` and `CrmChannelConnection`.

Selection toolbar extensions: an entity's `list.selection` component (React) is mounted when rows are
selected and the viewer has write access. It receives `ids` and `complete()` (clear selection and
reload after success). Commands must enforce their own server-side authorization.

Components can import `DatePicker` (date only) and `DateTimePicker` (local date and
24-hour time) from `@onno/ui`. They share the host calendar and emit ISO strings
at day and minute precision, respectively (`YYYY-MM-DD`, `YYYY-MM-DDTHH:mm`; local wall time).
Generated `LocalDateTime` fields use `DateTimePicker`.

CRM message bodies can be drawn by the app: `<CrmInbox messageRenderers={[…]} />` takes
`ChatMessageRenderer`s from `@onno/crm` with predicate/priority selection and a plain-text fallback.

`@onno/ui` also exports `ContextMenuContent` and `ContextMenuItem` for pointer-positioned and keyboard-invoked menus.

### Structured record tags

`EntityTags` is an `@onno/ui` component with `{ kind: "catalogs" | "documents", name, id,
readOnly? }`. It displays colored chips and supports selecting existing catalog tags and adding/removing
individual assignments. Render `<EntityTags kind="catalogs" name="customers" id={id} />` in any
component, for example a `recordPanels` entry of the entity's customization.

The web starter stores stable tag IDs, names, colors, and record assignments in `onno_tags` and
`onno_tag_links`. Libraries are scoped to the canonical entity kind/name, so tags can be reused
across its records. Case-insensitive names reuse a definition; removing a chip only removes that
record's assignment. Standard entity read/write permissions and the record's `RecordAccessPolicy`
scope apply (read scope to list a record's tags, write scope to change them). Optional
`TagAccessPolicy` beans add further checks; the CRM uses its workspace customer permissions.

Bind an ordinary application tag catalog with a `TagCatalog` bean (`scope`, `list`, and an idempotent
legacy `importTag`). The catalog owns names, colors, permissions, and soft deletion; the tagging
service owns record assignments. The host supplies any tag catalog and navigation.
The Add tag picker only searches/selects existing catalog entries. Legacy IDs and assignments
survive migration; catalog edits update every assigned chip and deleted tags leave the picker.

Internal CRM notes keep the inbox composer and use a compact inline reference picker: `@` selects people, `#` selects catalog/document records, and pasted local record links become references. Notes remain ordinary Onno comments, including permission checks and mention notifications. The host `CommentBody` renderer accepts a stored `body` and resolves links for the current viewer. CRM reply drafts and internal-note drafts stay separate.

### UI contributions

An app adds buttons or content to the host's named outlets without editing a host component:
`defineApp({ extensions: [...] })`. A contribution declares a globally unique namespaced `id`, a
`slot`, an optional ascending `order`, an optional synchronous `visible(context)`, and a React
`component({ context })`. Empty outlets add no content. A failing visibility predicate hides only
that contribution; a render error shows an isolated "unavailable" notice. Async work, loading state
and action errors belong to the contribution. Components remount when the outlet's
record/workspace/page changes.

| Outlet | Context / operations |
| --- | --- |
| `entity.list.actions` | Right-side toolbar actions; entity kind/name, selection, canWrite, refresh, navigate, collection commands |
| `entity.list.selection` | Shown with a selection; execute record commands for the selected IDs |
| `entity.list.context-menu` | Clicked record plus selection; close menu, refresh, navigate, record commands |
| `entity.form.actions` | Persisted record snapshot and canWrite; navigate |
| `entity.form.aside` | Right-side content next to the ordinary entity form; same form context |
| `crm.chat.header` | Selected conversation, workspace, canWrite/canReply, refresh, navigate, insertDraft, execute |
| `crm.chat.composer` | Same conversation context; draft insertion never sends and enforces the channel limit |
| `crm.chat.aside` | Same context, below the default content in the toggleable contact-details panel |
| `crm.contact.actions` | Customer record and permissions for application-authored content; no predefined workflow commands |
| `crm.chat.context-menu` | Clicked conversation and workspace, canWrite, navigation, closeMenu |

`context.execute` is optional and accepts a command name plus input. The CRM exposes `assign`, `close`, `reopen`, `status`, and
`logActivity` and fixes the target conversation itself; a contribution cannot redirect that command
by supplying a different conversationId. Existing backend roles, record/workspace checks, CSRF,
and business validation still authorize every write. Client visibility/permissions are presentation
hints, not a security boundary. Contributions are trusted application code, not sandboxed.
Form outlets deliberately omit refresh/write callbacks that could discard an unsaved form.

```tsx
import { defineApp, type UiExtension } from "@onno/ui";

const contactInsights: UiExtension = {
  id: "mycompany.contact-insights",
  slot: "entity.form.aside",
  order: 20,
  visible: (context) => context.name === "Customers" && !!context.recordId,
  component: ({ context }) => <section>Insights for {String(context.record?.description ?? "Contact")}</section>,
};

defineApp({ extensions: [contactInsights] });
```

A starter package may export ready-made contributions for the app to list. Provider-specific
contributions belong in consuming applications, not in the reusable CRM starter. Components can
render further namespaced outlets with `ExtensionSlot` and an explicit `ExtensionContext`.

Application header contributions can use IDs `example.crm.action.assign`, `.details`, `.close`,
`.reopen`, `.status`, and `.logActivity`. The contact action slot is empty by default; applications own any buttons and workflows they add.
They consume `context.actions` and the supplied callbacks. These components belong to the consuming
app; the CRM starter installs no default action-button contributions.

An application may own a Templates catalog, picker, and button, registering `example.crm.composer.templates` in `crm.chat.composer`; the CRM starter
contains no template catalog or picker. Replace that contribution
ID to customize it, or register additional IDs to add neighboring buttons. The
composer supplies `insertDraft` only in reply mode; the host preserves the existing
draft, enforces the channel length limit, and never sends on insertion. Empty
template catalogs remain hidden.

Applications optionally define conversation-status choices and transitions with a `CrmStateConfiguration` Java bean.
Define stable UUIDs once, then change
labels/colors freely; target those UUIDs for incoming messages, replies, close, and reopen. The
starter validates the transition targets and provides read-only projection for reference rendering.

Components can anchor a shared `PopoverContent` to an existing field using `PopoverAnchor` with `asChild`. Use this for input-driven suggestion popovers without adding a separate trigger button; preserve input focus via the popover autofocus callbacks. CRM controls use `@onno/ui`'s buttons, labels, inputs, selects, and popovers.

The CRM starter leaves its header-action and template-picker slots empty.
The inbox contact panel and context menu do not display tag controls; saved tag and template data is retained.

CRM channels use extensible string keys with connector-owned `CrmChannelDefinition` metadata
(`GET /api/crm/channels/types`); no fixed channel enumeration or Channels page is installed.
`<CrmConnections setups={[channelSetups]} />` takes provider setup forms as components; bundled provider
setup and branding live in the channels starter (`@onno/crm-channels`). The inbox's columns, sort and
filters are `<CrmInbox list={…} />` props. Status bindings read an existing host
catalog or enumeration through `CrmStateConfiguration.catalog(...)`/`.enumeration(...)`, with no
CRM status table or mirrored records. Channel keys and status UUIDs are breaking storage changes.

Priority is optional: bind a host catalog/enum with `CrmPriorityBinding.catalog(...)` or
`.enumeration(...)`; use `.options()` in an ordinary priority filter. No priority enum, default or
implicit column/filter is installed. The priority UUID is scoped to that host source.
Assignment, close/reopen, status-change and manual identity-linking HTTP actions are not bundled.
Host `Commands<Conversation>` beans declare ordinary record commands; extension buttons receive
their descriptors and call `context.execute(key, inputs)`. The scoped API is
`/api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions` (GET descriptors, POST `/{key}`
with `{inputs:{...}}`). It checks workspace write access, application read-only mode, command roles
and the commands' `availableWhen`/`enabledWhen` rules. CRM adds no business-field mutations or automatic history
messages for these actions. Hosts own the handler and any desired history records. Connector code
can still use the low-level identity-link service for provider routing.

### Pagination in custom list panes

Custom list renderers receive optional `hasMore`, `loadingMore`, `loadMoreFailed` and `loadMore()`
props. A renderer with its own scrolling pane must call `loadMore()` near that pane's bottom and
provide an accessible load-more/retry button (disabled while loading). Stop automatic retries after
`loadMoreFailed`; an explicit retry uses the same cursor. The host retains the scoped feed, filters,
query generation, row deduplication and loading guard. Do not fetch the entire catalog in a renderer. The host
shows a "Loading more…" status below the renderer while a window loads; a renderer that draws its
own opts out with a static `ownsLoadingMore = true` on the component.

Custom list renderers receive optional `ListRendererProps.total`, the server count matching the
current search and filters, independently of how many pages are loaded. A null/omitted total means
unknown. The inbox uses this count in its main chat header; folder counts remain scoped to loaded
rows and are labeled as the current view.

### Connection providers and account privacy

Contribute `CrmAccountProvider` beans for installed-provider metadata, supported account scopes,
availability/setup guidance, account views and commands. Pair them with a `CrmConnectionSetup`
component (`<CrmConnections setups={[…]} />`) for provider-owned authorization UI; it receives the
account (accountId, scope), server-derived `canManage` and `refresh`. Use `CrmConnectionAccounts` for credential-free ownership/inbox binding,
and private connector storage for secrets. Do not advertise personal scope without isolated
credentials and inbox routing: advertising `PERSONAL` also closes the generic CRM catalogs to
administrators. Existing `CrmChannelConnection` beans remain shared legacy accounts.
For explicit privacy boundaries, `EntityAccessPolicy.includesAdministrators()` opts out of the
ordinary ADMIN bypass. See the CRM starter README for full contracts and component props.

CRM workspace pagination preserves Java selectors and facet resolvers through request-local SQL
projections. Facet resolvers now receive bounded batches (maximum 500) and must produce values
per conversation independently of batch membership. Avoid global ranking inside the callback.
See [the pagination contract](HEADLESS_READ_API.md#collection-pagination) and
[CRM pagination](../onno-crm-starter/README.md#conversation-pagination) for performance limits.
