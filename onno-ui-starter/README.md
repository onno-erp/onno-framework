# onno-ui-starter

Spring Boot starter that serves the onno admin / back-office UI. It bundles a React SPA (built with
Node 20 by Gradle) and exposes a generic REST + **DivKit** (server-driven UI) layer over the
business model: catalogs, documents and registers get list/detail/form screens generated from
metadata, with no per-entity controller to write.

The same DivKit contract drives the React web client today and is intended to drive a native
(Flutter) client later, so screen layout, RBAC and theming are resolved server-side.

## Enabling

Auto-configuration kicks in when `onno-ui-starter` is on the classpath, a `MetadataRegistry` bean
exists (contributed by `onno-framework-starter`), and the UI is enabled (the default):

```yaml
onno:
  ui:
    enabled: true        # default
    path: /ui            # URL prefix the SPA is mounted under (use / for the web root)
    read-only: false     # default; true blocks all mutating REST calls
```

It registers the generic controllers (`/api/catalogs`, `/api/documents`, `/api/registers`), the
DivKit emitters (`/api/divkit/**`), the theme/config endpoints, an SSE event stream, the SPA index
controller, and a static-resource handler that serves the bundled frontend from
`classpath:/static/ui/`.

> The frontend is packaged into the jar by Gradle (`buildFrontend` → `processResources` copies
> `src/main/frontend/dist` to `static/ui/`). `static/ui/` is not committed; build the module to
> produce it.

### Configuration keys

| Key | Default | Purpose |
|-----|---------|---------|
| `onno.ui.enabled` | `true` | Master switch. Also gated on a `MetadataRegistry` bean being present. |
| `onno.ui.path` | `/ui` | URL prefix the SPA is mounted under. Baked into the served `index.html` (and returned as `basePath` from `GET /api/config`) so the client uses it as its React Router `basename` and deep-link prefix; the bare root redirects here. Set to `/` to mount at the web root. |
| `onno.ui.read-only` | `false` | When `true`, every mutating REST call (POST/PUT/DELETE, post/unpost, and custom server/page actions) returns `403 UI is in read-only mode`. |
| `onno.ui.settings.enabled` | `false` | Opt-in switch for the built-in Settings page (the `@Constant` editor) and its auto-injected admin nav entry. Off by default; an app that wants app-wide settings turns it on (or authors its own `Page` at `/settings`). |
| `onno.ui.theme.*` | empty map | Free-form theme key/value pairs, served verbatim from `GET /api/theme`. Each becomes a CSS custom property (`--{key}`) on the document root, so it overrides any design token the UI reads — including the [shape tokens](#shape-tokens) that reshape every button, control and card app-wide. |
| `onno.ui.messages.*` | empty map | Overrides for the framework's own chrome strings (see [Localizing the chrome](#localizing-the-chrome)). Each key (e.g. `login.title`, `action.new`) replaces the English default. Quote the dotted keys in YAML. |
| `onno.ui.update-check.enabled` | `true` | Poll onno-cloud for a newer framework release and show an "update available" notice. Fail-silent; set `false` to disable all outbound checks. |
| `onno.ui.update-check.url` | `https://cloud.onno.su/releases/v1/latest` | Release-announcement endpoint to poll. |
| `onno.ui.update-check.interval` | `24h` | Cadence after the first check (floored at 60s). |
| `onno.ui.update-check.initial-delay` | `1m` | Delay before the first check. |

### Shape tokens

Corner rounding is unified behind two CSS custom properties, so the whole UI reshapes from config
rather than per-component classes:

| Token | Drives | Default |
|-------|--------|---------|
| `--radius-control` | every interactive control — buttons, inputs, selects, filter chips, toggles | `9999px` (pill) |
| `--radius-card` | surfaces — cards, the list toolbar island, popovers, menus, dialogs | `0.9rem` |

Override either via `onno.ui.theme` (the key `radius-control` maps to `--radius-control`). Every
button and control across every page reads the same token, so one line restyles the app:

```yaml
onno:
  ui:
    theme:
      "radius-control": "0.5rem"   # square-ish controls instead of the default pill
      "radius-card": "0.75rem"
```

Avatars and status pills stay fully round independently of these (so squaring the controls never
squares a face-pile or an enum pill). This covers the React surfaces (lists, forms, detail action
buttons, dialogs); the server-rendered DivKit detail cards/tables carry their own corner radius.

### Update-available notice

When `onno.ui.update-check.enabled` is on (the default), `UpdateChecker` polls
`onno.ui.update-check.url` on a background daemon thread and compares the announced version to the
running framework version (baked into `META-INF/onno-build.properties` at build time). The result
rides along on `GET /api/config` as an `update` object — `{ available, current, latest, url }` — and
the web client raises a dismissible toast when `available` is true. Every failure (offline, non-200,
unparseable body, unknown local version) is swallowed, so a flaky network never produces a wrong or
alarming notice. The endpoint it polls is served by onno-cloud (`GET /releases/v1/latest`), which
returns `204` when no release is announced.

### Localizing the chrome

A deployment can already localize its **domain** — entity `title`, `@Attribute displayName`,
list-column labels, ref data. `onno.ui.messages` covers the framework's own **chrome**: action
buttons (`New`/`Write`/`Post`/`Delete`…), the delete-confirmation dialogs, the login screen, the
empty/loading states, the client-side validation messages, and the workspace tab titles. There is
one English default per key (`su.onno.ui.UiMessages.DEFAULTS`, mirrored in the web client's
`lib/messages.ts`); an override replaces it.

A workspace tab's name is the **domain** title, not a chrome string: the tab reads the entity's
localized `title` from the shell's route→title map (the same labels the sidebar shows), so a list
tab already follows the entity's language with no `onno.ui.messages` key. Only the record-tab verbs
are chrome — `tab.new` / `tab.edit` / `tab.duplicate` (`New {entity}` etc.) wrap that localized
name. A tab for an entity not placed in the nav falls back to the humanized route segment.

The home/dashboard entry is the one nav/tab label that can also be chrome: it uses the authored `/`
`Page`'s `title` when set (localize it with `b.title(...)`), otherwise the `nav.dashboard` key — so a
widget-grid dashboard with no authored page still localizes its sidebar item and tab via
`onno.ui.messages`. The built-in **Settings** surface (opt-in via `onno.ui.settings.enabled`) is the
same shape: its sidebar item and tab read `nav.settings`, and the default constant-editor page's
heading reads `settings.title` / `settings.subtitle` — all overridable via `onno.ui.messages`, unless
an authored `/settings` `Page` sets its own `title`/`subtitle`.

The resolved map (defaults + overrides) is the single label source for both layers: the server-side
DivKit builders read it directly, and it rides along on `GET /api/config` as a `messages` object the
web client overlays on its bundled defaults. Scope is **one language per deployment** — there is no
`Accept-Language` negotiation or runtime locale switch.

Because the keys contain dots, quote them in YAML so they bind as literal map keys (in a `.properties`
file use bracket notation: `onno.ui.messages[action.new]=Новый`):

```yaml
onno:
  ui:
    messages:
      "login.title": "Вход"
      "login.username": "Имя пользователя"
      "login.password": "Пароль"
      "action.new": "Создать"
      "action.save": "Записать"
      "action.post": "Провести"
      "status.posted": "Проведён"
      "status.draft": "Черновик"
      "empty.noRecords": "Нет записей"
```

This is distinct from `config("locale", …)` (see [`config(key, value)` reference](#configkey-value-reference)),
which only drives `Intl` number/currency formatting — it does not translate any chrome string.

## REST endpoints

All endpoints are under `/api/**`. Entity-scoped paths take a `{name}` that is the entity's
**display name**, not the Java class name: it is matched against the descriptor's `logicalName()`
with spaces and underscores stripped and lower-cased. A catalog declared
`@Catalog(name = "Properties")` is reached as `/api/catalogs/Properties` (and `properties`,
`Properties`, `propert_ies` all resolve too) — **not** by its class name `Property`. An unknown
name returns `404`.

### Catalogs — `/api/catalogs`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}` | Full list. With `?q=` and/or `?limit=` switches to a capped typeahead (default cap 50, max 200) for ref pickers. |
| GET | `/{name}/children?parent={uuid}` | Direct children (hierarchical catalogs only; else `400`). Omit `parent` for roots. |
| GET | `/{name}/tree` | Full nested tree (hierarchical only). |
| GET | `/{name}/{id}` | Single item. |
| POST | `/{name}` | Create. Body is a JSON map of `code`/`description`/`folder`/`parent` + attribute fields. Code auto-generated when omitted and the catalog auto-numbers. |
| PUT | `/{name}/{id}` | Partial update. Send `version` (or `_version`) for optimistic locking — a stale version returns `409`. |
| POST | `/{name}/{id}/duplicate` | Server-side copy: same description/attributes/parent, fresh id + code, description suffixed with `duplicate.copySuffix` (" (copy)" by default). Secret attributes start unset. Backs the list's ⌘C/⌘V. |
| POST | `/{name}/batch-delete` | Soft-delete `{ids: […]}` in one request (≤500). Per-id failures don't abort; returns `{ok, failed, total}`. Backs the list's batch Delete N. |
| DELETE | `/{name}/{id}` | Sets the deletion mark (soft delete). |

### Documents — `/api/documents`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}?from=&to=` | List, optionally filtered by date range. |
| GET | `/{name}/{id}` | Single document. |
| POST | `/{name}` | Create. Body carries header fields + tabular-section arrays keyed by section name. Number auto-generated when omitted and the document auto-numbers. |
| PUT | `/{name}/{id}` | Partial update; supplied tabular sections are replaced wholesale. Optimistic locking via `version`/`_version` (`409` on conflict). |
| POST | `/{name}/{id}/post` | Run posting (writes register movements). |
| POST | `/{name}/{id}/unpost` | Reverse posting. |
| GET | `/{name}/{id}/posting-preview` | Preview movements without writing them. |
| POST | `/{name}/{id}/duplicate` | Server-side copy: attributes + line items, fresh id + number, dated now, unposted. Secret attributes start unset. Backs the list's ⌘C/⌘V. |
| POST | `/{name}/batch-delete` | Soft-delete `{ids: […]}` in one request (≤500, auto-unposting posted ones). Per-id failures don't abort; returns `{ok, failed, total}`. Backs the list's batch Delete N. |
| DELETE | `/{name}/{id}` | Soft delete (auto-unposts first if posted). |

### Registers — `/api/registers`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}/movements?from=&to=` | Raw movements. |
| GET | `/{name}/balance?{dim}={value}...` | Current balances (query params are dimension filters). |
| GET | `/{name}/turnover?from=&to=&{dim}=...` | Period turnover; `from` and `to` are required. |

The catalog/document **list grid** is fed window-by-window from a dedicated feed so a 100k-row entity
never ships whole to the client. The default is **keyset (seek) pagination**: indexed, constant-time
at any depth, and immune to the skip/duplicate that offset paging suffers when rows shift mid-scroll.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/list/catalogs/{name}?cursor=&limit=&sort=&dir=&q=&{filters}` | One keyset window. Omit `cursor` for the first window; echo back the `nextCursor` from the previous response for the next. Envelope: `{ rows, nextCursor, hasMore }`. |
| GET | `/api/list/documents/{name}?cursor=&limit=&sort=&dir=&q=&from=&to=&{filters}` | Same, plus the optional `from`/`to` date range. Default order is newest-first (`_date`). |
| GET | `/api/list/{kind}/{name}/groups?groupBy=&granularity=&{q,filters}&agg=fn,col` | Backend **grouping**: one header per `GROUP BY groupBy` value (or, for a date column, per `granularity` — `day`/`month`/`year` — bucket), over the same WHERE as the flat list. Envelope: `{ groups: [{ label, color?, count, values[], expand[] }], capped }`. Each header's `expand` is the filter params to replay on the flat feed to load that group's rows (an `eq`, or a `ge`/`le` range for a date bucket). Headers cap at 200 (`capped: true`). |
| GET | `/api/list/{kind}/{name}/aggregate?metric=&field=&groupBy=&groupByDate=&seriesBy=&filter=&dateField=&from=&to=` | The **widget aggregate** read behind `chart`/`stat`/`sparkline`/`gauge` (#199): a server-side `GROUP BY groupBy[, seriesBy]` (`groupByDate` = `minute`…`month` buckets a timestamp via `DATE_TRUNC`; `dateField`+`from`/`to` window the rows) returning `{ buckets: [{ key, label?, series?, seriesLabel?, value, value2? }], truncated, span? }` — O(buckets) instead of the whole table. Blank `groupBy` → one grand-total bucket; `metric2`/`field2` add a combo chart's second measure; enum/`Ref` buckets carry a resolved `label`; buckets cap at 1000 (`truncated: true`). |

- **No COUNT by default.** `hasMore` (one extra row fetched under the hood) is what the scroller
  needs to keep loading, so the hot path never pays for a full count. Add `?count=exact` for a precise
  total, or `?count=estimate` for a cheap PostgreSQL planner figure (omitted on H2 or when a
  search/filter is active).
- **Cursor is opaque and self-describing.** It encodes the sort column + direction + the last row's
  `(sortValue, _id)`; a cursor replayed against a different sort is ignored (paging restarts) rather
  than seeking to a wrong position. It is a position, not a credential — every value is bound, never
  inlined.
- **Indexes.** The schema engine auto-emits composite `(_code, _id)` / `(_description, _id)` (catalogs)
  and `(_date, _id)` (documents) so the seek is an index-only range scan; on PostgreSQL it also adds
  `pg_trgm` GIN indexes so the `q=` substring search is indexed instead of a full scan.
- **Offset mode.** Passing `?offset=N` switches to `LIMIT/OFFSET` with the `{ total, offset, rows }`
  envelope. The grid uses this for **paged** lists (numbered pages need a total and jump-to-page,
  which a cursor can't give); **infinite** lists send `cursor` instead.

**Feed mode — infinite scroll vs numbered pages.** The grid renders one of two ways, chosen per
entity and defaulted globally:

- **`INFINITE`** (default) — cursor-scrolls the keyset stream above: loads a window, then more as you
  scroll, virtualized so the DOM stays small; header shows a cheap `?count=estimate` (or the loaded
  count). The natural fit for large, append-heavy lists.
- **`PAGED`** — numbered offset pages with a Prev/Next pager and an exact total. Best for small or
  well-filtered lists where jump-to-page and a precise count matter more than depth performance.

Author it on the view (`list.feed(FeedMode.PAGED)`, `list.pageSize(25)`) or set the app-wide default
with `onno.ui.list.default-feed` (`infinite` | `paged`) and `onno.ui.list.page-size`. A register's
report surface is always infinite (its movement log is depth-scrolled).

**Grouping — backend `GROUP BY` with lazy expansion.** Declare `list.groupable("status", "date", …)`
and the toolbar gains a "Group by ▾" picker (None = the flat list). Picking a column swaps the flat
table for collapsible group headers fetched from `/groups` (one per value; a date column offers a
day/month/year granularity and buckets by period); each header shows the group's row count and any
`list.aggregate(field, Agg.SUM|AVG|MIN|MAX)` subtotals. Expanding a header lazily loads that group's
rows through the **same** feed — the server hands each group the exact filter to replay — so grouping
never double-counts and honours the active search/filters/sort. Group values that resolve to a
ref/enum show their label (and enum colour); a null group is shown but not expandable.

The register **report surface** is likewise fed window-by-window (newest-first, server-sorted). Its
default response is the same `{rows, nextCursor, hasMore, total}` envelope as the entity feeds (the
cursor is the next window's offset, treated as opaque by the client); passing `?offset=` explicitly
keeps the legacy `{total, offset, rows}` page. Both feeds honor the grid's declarative filter params
(`eq`/`in`/`like`/`prefix`/`ge`/`le`, validated against the register's own columns) — the movements
tab ships a built-in `_period` date-range facet and a `_movement_type` Receipt/Expense facet, and
movement rows carry a localized `_movement_type_display` + `_movement_type_color` so the type renders
as a colored status pill:

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/list/registers/{name}/movements?cursor=&limit=&sort=&dir=&from=&to=&eq=&ge=&le=` | One window of movements + the live total, for the virtualized register surface. |
| GET | `/api/list/registers/{name}/balance?cursor=&limit=&sort=&dir=&eq=&in=` | One window of current balances + the live total (BALANCE registers). |

### DivKit server-driven UI — `/api/divkit`

These return DivKit card JSON resolved for the caller's persona/roles, theme (`?theme=`) and
viewport (`?viewport=`). `GET /shell` is the fast chrome (top bar + nav, no data); the rest are
data-bearing surfaces.

| Path | Surface |
|------|---------|
| `GET /shell` | Nav + account chrome. |
| `GET /home` | Dashboard / authored home page. |
| `GET /account`, `GET /menu` | Mobile account card and "More" nav hub. |
| `GET /catalogs/{name}`, `/catalogs/{name}/{id}`, `/catalogs/{name}/new` | Catalog list, record surface and create form. The record surface **is the editable form** (1C-style object form): writers edit in place and Save stays on the page; a viewer without write access gets the same form disabled. `/{id}/edit` remains as a back-compat alias for `/{id}`. |
| `GET /documents/{name}`, `/documents/{name}/{id}`, `/documents/{name}/new` | Document list, record surface and create form — same combined form; `/{id}/edit` is a back-compat alias. |
| `GET /registers/{name}` | Register surface: a virtualized movement log (a Balance/Movements tab pair for BALANCE registers), each fed page-by-page from `/api/list/registers/{name}/…`. |

> The DivKit surfaces are an **allowlist**: a catalog or document is only visible if an `EntityView`
> bean declares it for the active profile. A surface with no matching view returns `404`, even when
> the underlying REST endpoint would serve it.
>
> This gates **reachability**, not **nav presence** — the two are separate. The sidebar is
> **curated**: a catalog/document/register shows in it only if a `Layout` section lists it
> (`spec.section("Licensing").icon("key").catalog(License.class)`). An entity that has an
> `EntityView` but is in no section is still reachable by its direct route (`/catalogs/{name}`) — it
> just won't appear in the nav. So an `EntityView` is **necessary but not sufficient** for nav
> presence: no view → `404`; view but no section → reachable but unlisted; view + section → in the
> sidebar. (Earlier versions auto-listed unclaimed catalogs under default `CATALOGS`/`REGISTERS`
> groups; that was removed.)

### List row actions

Every list row (DivKit-rendered lists and the virtualized `EntityListWidget` alike) supports:

- **Right-click** → a context menu with **Open**, **Duplicate**, **Copy link** and **Delete**
  (Open lands on the editable record surface, so there is no separate Edit item). **Copy link**
  puts the row's shareable deep link (`{origin}/{kind}/{name}/{id}`) on the clipboard — paste it
  into a new tab to land straight on that record.
- **Delete key** (macOS **fn+Backspace**, or the dedicated Del key) → deletes the row **under the
  pointer** — the one the hover highlight marks. Ignored while typing in a field or while a menu or
  dialog is open.

Both delete paths open the in-app confirmation dialog and then issue `DELETE /api/{kind}/{name}/{id}`
(soft delete), so the server still enforces write access — a read-only user (or one without the
entity's write role) gets a `403`, never a silent delete.

### Sharing a link to a surface

Every surface is a real browser URL (the app runs on `BrowserRouter`), so any list, record, page,
dashboard or register is shareable by its address. Besides the row menu's **Copy link**, on the
desktop islands layout **right-click a workspace tab → Copy link** copies the deep link to whatever
that tab shows — covering pages, dashboards and registers that aren't list rows. Pasting a link into
a fresh tab opens the app straight on that surface (after login if needed).

### Custom & state-aware row actions

An `EntityView.actions(ActionSpec)` declares custom buttons on the list (`ActionScope.TOOLBAR` /
`ROW`) or the detail surface (`DETAIL`); each runs a server `handler(ctx -> ActionResult)` or
`navigate(url)`. A **row** action's icon, label, visibility and enabled state may be **functions of
the row** instead of fixed, so one control adapts to each record — a `pause` "Suspend" on a running
row flipping to a `play` "Resume" once stopped, or a button shown only where it applies:

```java
public void actions(ActionSpec a) {
    a.action("toggle").scope(ActionScope.ROW)
     .icon(row  -> row.enumValue("status", Status.class) == Status.STOPPED ? "play" : "pause")
     .label(row -> row.enumValue("status", Status.class) == Status.STOPPED ? "Resume" : "Suspend")
     .visibleWhen(row -> row.enumValue("status", Status.class) != Status.ARCHIVED)
     .enabledWhen(row -> row.canToggle())            // any predicate over the row
     .handler(ctx -> { svc.toggle(ctx.id()); return ActionResult.refresh("Toggled"); });
}
```

Any action (entity or page) may declare `.roles("ACCOUNTANT", "MANAGER")` — the server then rejects
callers holding none of them (`ADMIN` always passes). For an entity action this is a finer gate *on
top of* the entity's write roles; unset means the entity's write access alone decides.

Each function receives an `ActionRow` — a read-only view of the row the list already rendered
(`id()`, `text(col)` for the display value, `enumValue(col, Type)` to read an enum column back, or
`get(col)`/`values()` for the raw map). They're evaluated **server-side per row** as the list page
is served (no extra query — the row is already in hand) and shipped to the grid under each row's
`_actions`; the button falls back to the static `icon`/`label` when a function isn't set. Per-row
functions apply to `ROW` actions only — toolbar/detail buttons have no row context and use the fixed
icon/label. A static row action (no functions) costs nothing: the list ships its rows untouched.

A `DETAIL` action lands in the record surface's header overflow (⋯) menu by default (beside the
form title), but honors the same placement override the built-in `unpost`/`duplicate`/`delete`
actions do — promote a key workflow action to a primary button (given the brand accent), keep it in
the menu, or hide it, from the entity's `fields(...)`. `f.action("post").hidden()` hides the form's
built-in Post button:

```java
public void fields(EntityConfigBuilder f) {
    f.action("advanceStatus").primary();   // prominent button next to Post, instead of buried in ⋯
    f.action("recalc").inMenu();           // the default — overflow menu
    f.action("archive").hidden();          // dropped from the UI (still reachable via REST)
}
```

### Action forms — collect input before the handler runs

A server action may declare a **form**: clicking it opens a modal dialog with the declared fields
(same builder DSL as toolbar inputs, plus `.required()` and `InputType.TEXTAREA`), and the handler
receives the submitted values via `ActionContext.input(key)` — the "Cancel with a reason" idiom:

```java
a.action("cancel").label("Cancel order").icon("ban").scope(ActionScope.ROW)
 .form(f -> f.input("reason").label("Reason").type(InputType.TEXTAREA)
             .placeholder("Why is this order cancelled?").required())
 .handler(ctx -> { service.cancel(ctx.id(), ctx.input("reason")); return ActionResult.refresh("Cancelled"); });
```

Works on every scope and placement — row button, toolbar, detail header, the row context menu, and
batch mode (the dialog collects once, then the values are sent with the action for every selected
record). Form values are merged over the ambient toolbar-input values in the same
`ActionContext.input(...)` namespace, so a key used by both is won by the form.

### Row context menu, submenus & batch selection

Right-clicking a list row opens a context menu with the built-ins (Open / Edit / Duplicate / Copy
link / Delete, with their keyboard shortcuts) **plus the entity's custom ROW actions**. A row action
placed with `.menu("…")` renders inside a **submenu** with that label instead of as an inline row
icon button — actions sharing a label group together in declaration order. The per-row functions
(`label(row -> …)`, `visibleWhen`, `enabledWhen`) still apply to menu entries:

```java
for (OrderStatus st : OrderStatus.values()) {
    a.action("status-" + st.name().toLowerCase()).scope(ActionScope.ROW)
     .menu("Change status").label(labelOf(st)).color(colorOf(st))
     .visibleWhen(row -> row.enumValue("status", OrderStatus.class) != st)
     .handler(ctx -> setStatus(ctx.id(), st));
}
```

`color("#059669")` gives context-menu entries a compact swatch, useful for enum/status submenus.
`logo(url)` is also honored there, so an "Assign" submenu can show employee photos.

Rows also support **batch selection**: ⌘/Ctrl-click toggles a row, Shift-click selects the range
from the last toggled row, Esc (or the toolbar's "N selected ✕" chip) clears. With the list
engaged (a selection active, or the cursor over a row), **⌘A** selects every loaded row and
**⇧⌘↓ / ⇧⌘↑** extend the selection from the anchor to the bottom / top of the loaded set — an
infinite feed selects the rows loaded so far, not the whole server-side result (⌘A toasts a hint
when more rows exist). Batch operations run as **one request** against the batch endpoints
(`POST /api/actions/{kind}/{name}/{key}/batch`, `POST /api/{kind}/{name}/batch-delete`), with a
loading → summary toast — a 200-row batch isn't 200 round-trips and survives the tab closing
mid-run. Esc layers cleanly: an open menu takes the first press, the selection the next, the tab
the last. Right-clicking a
selected row switches the menu to batch mode: every custom **server** row action (flat or submenu)
runs over each selected id sequentially with a summary toast, and Delete becomes a two-step
"Delete N". Navigation actions and per-row visibility overrides don't apply in batch mode — the
handler decides per record.

**⌘C / ⌘V on rows.** Copy (the selection, else the hovered row) writes two clipboard flavours:
`text/plain` TSV of the visible columns exactly as rendered — pastes straight into a text file or
a spreadsheet — and an app payload with the record ids. Paste on the same entity's list creates a
**server-side copy** per id via `POST /{name}/{id}/duplicate` (fresh id + code/number, catalog
descriptions suffixed " (copy)" — the `duplicate.copySuffix` message key — documents dated now and
unposted, line items included, secret attributes left unset), capped at 50 records per paste. A
focused input or an active text selection keeps the browser's native copy/paste. Pasting on a
different entity's list, or without write access, does nothing. Note the payload carries ids, not
a snapshot: pasting re-reads the record, so a copy made before an edit pastes the edited state.

**Unsaved-changes guard.** A form tab (new / edit / duplicate) with typed-but-unsaved input asks
"Discard changes?" before closing via the tab ✕ or Esc. Save and the form's own Cancel are
explicit outcomes and close without asking; programmatic closes (post-save, post-delete) never
prompt.

### New-record forms & ref pickers

A **New** form seeds its inputs from a fresh instance of the entity, so a domain field initializer is
the idiomatic place for a default — it pre-fills the form, not just new records written through code:

```java
public class Order extends DocumentObject {
    private OrderStatus status = OrderStatus.NEW;   // pre-selected in the New form
    private Integer photoCount = 1;                 // pre-filled
}
```

(An entity with no usable no-arg constructor opens blank, as before. A `Ref` default can't be a
literal initializer, so it isn't seeded.)

A **ref picker** (the typeahead for a `Ref` field) searches every text column of the target — code,
description/number, and each String attribute — so a record is findable by a secondary attribute
(e.g. a phone), not just its name. To also *show* that attribute under each option's name (to
disambiguate same-named records), name it on the ref field from the entity's `fields(...)`:

```java
f.field("customer").refSecondary("phone");   // shows the customer's phone under the name in the picker
```

The named field is on the ref's **target** entity; the value already rides along in the picker
payload, so this only tells the client which extra line to render.

The picker's pinned **"+ New"** row opens the target's full create form in a side pane; when that
form saves, the new record's id is handed straight back to the picker — the field is set to the
record the user just created (no detail navigation, no re-finding it in the list). Cancelling the
form, or manually picking another option first, drops the hand-off.

### Edit-form layout

The generic edit form honors the `FieldHintBuilder` layout hints:

- `.group("Heading")` — fields sharing a group render as their own card with that heading, in
  first-appearance order; ungrouped fields (and `code`/`description`) form the leading unlabeled
  card. Long forms read as sections instead of one endless column.
- `.width("half")` (or `"1/2"`) — the field takes half a row on wide screens, so short fields
  (dates, amounts, refs) sit side by side. Anything else spans the full row.
- `.widget("textarea")` — a multi-line control. A `String` attribute with `length` > 1000 (or
  unbounded) gets a textarea automatically even without the hint.

An edit form's header also shows the record's identity (code/number · description) and, for a
postable document, its Posted status pill.

## Dashboard widgets

Widgets are authored on a `Page` (or `layout.widget(...)`) with the `WidgetBuilder` DSL and
compiled to DivKit. `count`/`metric` render as native big-number cards (resolved server-side);
every other type — the built-in `chart`/`stat`/`sparkline`/`gauge`/`calendar`/`kanban`/`list` and
any app-registered type — is emitted as an `onno-widget` custom block that the client renders with
a React component.

```java
b.widget("Revenue").type("metric").width("1/4").document(Bill.class)
 .config("metric", "sum").config("metricField", "gross").config("currency", "EUR");
```

`width("1/4"|"1/3"|"1/2"|"2/3"|"3/4"|"full")` sets the widget's share of a layout row; widgets flow
left-to-right until a row fills. `rowBreak()` forces the widget to start a fresh row even when the
previous one still has room — use it to keep a section from being pulled up beside leftovers of the
row above (no effect on the single-column mobile layout).

Each `count`/`metric` card resolves a server-side aggregate (one SQL query). The dashboard renderer
resolves them **concurrently** and de-duplicates identical `(entity, metric, field, filter)` queries,
so a wide dashboard isn't N sequential round-trips. The fan-out is bounded by
`onno.ui.dashboard.widget-parallelism` (default 8) — keep it below the datasource's pool size; set it
to `1` for the old sequential behaviour.

### Widget types

| `type(...)` | Renders | Notes |
|-------------|---------|-------|
| `count` | KPI card — row count | Honours `filter`. |
| `metric` | KPI card — aggregated value | `metric` = `sum`/`avg`/`min`/`max` over `metricField`; honours `filter`, `currency`/`format`. Works on `document`, `catalog`, or a register resource. |
| `chart` | recharts bar/line/area/donut/**pie**, single- **or multi-series** | Source from a `document`/`catalog` (fetched **pre-aggregated** from `GET /api/list/{kind}/{name}/aggregate` — a server-side `GROUP BY`, O(buckets) over the wire, #199) or a `register` (server-side turnover). `seriesBy` splits into colored series; `stacked` stacks them. |
| `stat` | KPI tile with trend | Headline aggregate + period-over-period delta + a sparkline of the series. |
| `sparkline` | Compact KPI tile | Headline aggregate + an inline sparkline (no delta). |
| `gauge` | Radial progress gauge | An aggregate filled toward a `target`. |
| `calendar` | FullCalendar | Documents only; drag-to-reschedule. |
| `list` | Recent-records list | Configurable title/secondary/amount/date. |
| `kanban` | Drag board grouped by a field | |
| `map` | MapLibre geometry map | Plots records on a theme-aware monochrome basemap (no API key). Geo source: `geoField` (a `"lat,lng"` string) **or** `latField`+`lngField` for markers, and/or `geoJsonField` for shapes (paths/areas); features link to the record and label from `.titleField(...)`. |
| *(custom)* | App-registered React component | Register on the client with `registerWidget("heatmap", HeatmapWidget)`. |

> `stat`, `sparkline` and `gauge` read the same source + aggregate config as `chart`
> (`metric`/`metricField`, `groupBy`/`groupByDate`); `stat`/`sparkline` also take `kind` =
> `area` (default) or `line` for the sparkline shape, and `gauge` takes a `target`.

### `config(key, value)` reference

| Key | Applies to | Effect |
|-----|-----------|--------|
| `metric` | count, metric, chart | `count` (default) or `sum`/`avg`/`min`/`max`. |
| `metricField` | metric, chart | Column aggregated by a non-count metric (a register resource for register sources). |
| `filter` | count, metric, chart, stat, sparkline, gauge, list, calendar | Safe predicate, e.g. `status != 'DRAFT' AND _posted = true`. Applied server-side for `document`/`catalog` sources — KPI cards aggregate with it; the data widgets pass it to `/api/list/...?filter=`. Columns are validated; values are always bound (never inlined) — see `WidgetFilter`. Quote string values compared to a `VARCHAR` column (e.g. `season = '2026'`) so Postgres doesn't reject an int/text mismatch. |
| `currency` | metric, list, calendar, chart | ISO code (e.g. `EUR`) → currency formatting. |
| `format` | metric, list, calendar, chart | `integer` / `decimal` fraction-digit policy when not a currency. |
| `locale` | metric, list, calendar, chart | BCP-47 locale for number/currency grouping. |
| `currencyField` | list, calendar | Per-row column holding a currency code (overridden by `currency`). |
| `kind` | chart | `bar`/`line`/`area`/`donut`/`pie`. Unknown kinds warn and fall back to `bar`. For `stat`/`sparkline` it picks the sparkline shape (`area` default, or `line`). |
| `groupBy`, `groupByDate` | chart, stat, sparkline | Bucket field, and `minute`/`hour`/`day`/`week`/`month` for date buckets (date buckets are ordered chronologically). On a chart the granularity auto-follows the shared time range — sub-day windows bucket by hour/minute — and stays overridable in the explore view. |
| `presets`, `default` | timeRange | The shared picker's quick-picks and starting window. `presets` is a comma list of duration ids (`<n><unit>` where `s`/`m`/`h`/`d`/`w`/`M`/`y` are second…year — note `m`=minute, `M`=month — plus `all`), e.g. `15m,1h,24h,7d,30d,90d,1y,all`. `default` names one of them (e.g. `30d`). Omit both for the built-in ladder defaulting to the last 30 days; a user's saved selection always wins over `default`. |
| `seriesBy` | chart | Field that splits the chart into one colored series per distinct value (multi-series `bar`/`line`/`area`). Ignored by `pie`/`donut`. Series rank by total; the tail beyond the palette folds into "Other". |
| `stacked` | chart | `true` to stack a multi-series `bar`/`area`. |
| `colors` | chart, stat, sparkline, gauge | Override series colors: a comma list of aliases (`primary`/`success`/`warning`/`destructive`/`muted`), palette slots (`chart-1`..`chart-8`), or raw CSS colors (`#8b5cf6`, `hsl(...)`). Applied slot-by-slot; unset slots fall back to the theme palette (`--chart-N`). |
| `target` | gauge | The 100% mark the ring fills toward; with none, the gauge shows the bare value in a full ring. |
| `titleTemplate` | list | `"{guest_name} — {property_display}"`; unknown fields render empty. |
| `secondaryField` | list, calendar | Comma-list of fields for the second line (first non-empty wins). |
| `amountField` | list, calendar | Column for the trailing money figure (defaults to `total`/`gross`-style fields). |
| `dateField` | list, calendar | Column for the date (also `.dateField(...)` on the builder). |
| `geoField` | map | Field holding a `"lat,lng"` string marker point (what `.widget("map")` writes). |
| `latField`, `lngField` | map | Numeric latitude/longitude fields, when the point is stored split (used when `geoField` is unset). |
| `geoJsonField` | map | Field holding GeoJSON geometry — points, paths, areas (what `.widget("geojson")` writes). Plotted alongside any marker point. |

> A register-backed `metric`/`chart` sums a register **resource** over its turnover; `metricField`
> must name a resource column, and `filter` may reference its **dimensions**.

### Authoring a custom widget (consumer app — no frontend project)

The framework renders a fixed set of widget types. To ship one it has no built-in for — an advanced
filter, an unusual chart, an event log — write a React component in **your Java project** and apply
the `su.onno.widgets` Gradle plugin. No `package.json`, no `npm`, no frontend fork.

1. Apply the plugin:

   ```kotlin
   // build.gradle.kts
   plugins { id("su.onno.widgets") }
   ```

2. Write the widget in `src/main/widgets/EventLog.tsx` using `@onno/widget-sdk` (types + hooks + a
   read-only data client; the SDK is bundled in the Gradle plugin, so it resolves with no npm access):

   ```tsx
   import { registerWidget, useEffect, useState, api, type WidgetProps } from "@onno/widget-sdk";

   function EventLog({ widget }: WidgetProps) {
     const [rows, setRows] = useState<any[]>([]);
     useEffect(() => { api.listDocuments(widget.entityName).then(setRows); }, [widget.entityName]);
     return <ul className="text-sm text-foreground">{rows.map((r) =>
       <li key={String(r.id)}>{String(r._date)} — {String(r._number)}</li>)}</ul>;
   }
   registerWidget("eventLog", EventLog);
   ```

3. Declare the widget server-side with a matching `type(...)` — its `.config(...)` values arrive as
   `widget.extraConfig`:

   ```java
   b.widget("Recent activity").type("eventLog").document(Payment.class)
       .config("amountField", "amount").config("currency", "EUR");
   ```

`./gradlew bootJar` compiles each `.tsx` (managed Node + esbuild, React aliased to the host SPA so
the output is a ~1 KB module with no React of its own) into `onno-plugins/<name>.js` on the classpath.
The starter scans that location, serves the modules under `{onno.ui.path}/plugins/**`, and advertises
them as `pluginScripts` from `GET /api/config`; the SPA dynamic-imports each at boot and each
self-registers. An unregistered `type(...)` renders a labelled placeholder rather than vanishing.

Config: `onno.ui.plugins.enabled` (default true), `onno.ui.plugins.extra-urls` (load extra modules,
e.g. from a CDN). Dev loop: `./gradlew compileWidgetsWatch` rebuilds on change. Plugin JS runs
first-party in the app origin (full session) — author it as trusted code.

**Styling:** the Gradle plugin runs **Tailwind over `src/main/widgets`** and emits `onno-widgets.css`
(advertised as `pluginStyles` from `GET /api/config`, injected by the SPA at boot). So utility classes
in your widget's own markup — including uncommon ones (`border-l`) and arbitrary values (`-left-[5px]`)
the host never emits — produce real CSS. It's utilities-only with **preflight off** and carries the
host tokens (`bg-primary`, `rounded-card`, …), so it matches the product without fighting the host
stylesheet. Caveats: only files under `src/main/widgets` are scanned (class names in imported helpers
or built by string concatenation aren't seen — keep them literal), and dynamic colors still want
inline `style` with `hsl(var(--primary))` / `hsl(var(--border))`.

**UI primitives:** need a dropdown, date picker, toggle, or button? Import the host's real
design-system controls straight from the SDK — they match the product exactly (keyboard nav, mobile
drawers, theming) and carry host-emitted classes:

```tsx
import { Segmented, DatePicker, useState } from "@onno/widget-sdk"; // by name, or from the `ui` object
// also Select, Button, Popover, Badge, Checkbox, Switch, Input, Card, …

function ViewSwitch() {
  const [view, setView] = useState("day");
  return <Segmented value={view} onChange={setView}
    options={[{ value: "day", label: "Day" }, { value: "week", label: "Week" }]} />;
}
```

### Registering a widget from framework/SPA source

Contributors extending the bundled SPA register built-ins directly, the same call the SDK proxies via
`window.onno`:

```ts
import { registerWidget } from "@/lib/widget-bridge";
registerWidget("heatmap", HeatmapWidget); // server: b.widget("Load").type("heatmap").document(Shift.class)
```

### Custom list renderers (tiles / cards / gallery)

An entity's list can delegate its **body** to a plugin-registered component while the framework
keeps everything else: the search bar, declarative filters, sorting, the feed (infinite scroll or
pages, with the pager), live SSE refresh and the toolbar all still work and drive exactly the rows
the renderer receives. Declare it from the entity's `list(ListSpec)`:

```java
@Override public void list(ListSpec list) {
    list.columns("description", "author", "price");   // still the data contract
    list.custom("bookTiles").label("Shelf");          // Table ⇄ Shelf toggle in the toolbar
    // or: list.custom("bookTiles").defaultView();    // open on the tiles, like map()
}
```

Register the renderer the same way as a dashboard widget — same registry, list-shaped props:

```tsx
import { registerListRenderer, type ListRendererProps } from "@onno/widget-sdk";

function BookTiles({ rows, list, open }: ListRendererProps) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))", gap: 12 }}>
      {rows.map((r) => (
        <button key={String(r._id)} onClick={() => open(r)}>{String(r._description)}</button>
      ))}
    </div>
  );
}
registerListRenderer("bookTiles", BookTiles);
```

The component receives `rows` (the current window — everything loaded so far in infinite mode, the
active page in paged mode), `list` (the descriptor slice: `kind`, `name`, `title`, resolved
`columns` with labels/widgets/formats, `canWrite`), and `open(row)` / `openUrl(row)` to open a
record's detail pane. A type with **no registered renderer degrades to the default grid** — the
toggle simply doesn't appear (same philosophy as a `map()` whose geo fields don't resolve), so a
missing/broken plugin never blanks the list. The chosen view persists in the URL (`?view=…`) like
the map toggle; the toggle's label falls back to the `list.customView` message ("Cards") when no
`.label(...)` is authored. The bookstore example ships one end to end: `BookView` declares
`list.custom("bookTiles").label("Shelf")` and `example/src/main/widgets/BookTiles.tsx` renders the
shelf. The same styling gotcha as widgets applies: compile happens outside the SPA's Tailwind
build, so inline styles for layout, theme variables for color.

## Maps

Geolocation renders on **MapLibre GL** over a minimal **monochrome basemap that flips light/dark
with the app theme** — no API key, no billing, and a collapsed ⓘ attribution. Data geometry is
tinted with the brand color (`--primary`). Two storage shapes, both on a plain String attribute:

- a **point** — a single `"lat,lng"` string (the simple picker / legacy format), or a numeric
  `lat`/`lng` pair on the read surfaces;
- **geometry** — a GeoJSON string holding points, paths (lines), and areas (polygons).

The built-in basemap is the keyless CARTO monochrome raster; apps that want a fully recolored vector
basemap or an **offline self-hosted Protomaps** style can pass a style override to the map components.

**1. Field inputs.** Opt a String attribute into a map control from the entity's `fields(...)`:

```java
f.field("location").widget("map");        // single point → "lat,lng" (click/drag a marker, or type)
f.field("serviceArea").widget("geojson"); // geometry editor → draw points/lines/polygons (GeoJSON)
```

The geometry editor has Point / Line / Area tools: click to add vertices, double-click or **Finish**
to complete a line/area, **drag the handles** to reshape, right-click a shape to delete. The
detail/read view of either field renders the stored geometry as a small map automatically.

**2. Dashboard widget** — plot a catalog/document's records on a `Page`:

```java
b.widget("Stores").type("map").width("full").catalog(Store.class)
 .config("geoField", "location")          // marker point ("lat,lng"); or latField/lngField
 .config("geoJsonField", "service_area")  // optional shape (GeoJSON) — paths/areas
 .titleField("name");                      // popup label (falls back to a system identifier)
```

**3. List map view** — add a Table ⇄ Map toggle to an entity's list, from its `list(ListSpec)`:

```java
spec.map().field("location").label("name");            // "lat,lng" marker
spec.map().lat("latitude").lng("longitude");           // split numeric fields
spec.map().geoJson("serviceArea");                     // GeoJSON shapes (combine with a point if you like)
spec.map().field("location").defaultView();            // open on the map, not the table
```

Features in the widget and list views link back to the record; records with no geometry are skipped.
A misconfigured map (a geo source that doesn't resolve) degrades to no map rather than failing the
surface.

Markers **cluster** automatically: nearby points collapse into brand-colored count badges that
split apart as you zoom (clicking a badge zooms into it), so the map stays legible with thousands
of records. On its own list route the map fills the same height the table would; embedded in a
dashboard it keeps the widget's fixed height. The list map pulls rows in server-page batches up to
4 000 records and says so in its count chip when the entity has more. Clicking a marker opens a
popup card with the label, the record's code/number, and an Open action; overlapping markers show
a list of every record at that spot. The chip/popup strings localize via the `map.*` keys in
`onno.ui.messages`.

## Page action buttons

`PageBuilder.actions(heading, spec)` adds a section of buttons to a page (the same `ActionSpec` an
`EntityView.actions(...)` uses for list/row buttons). Each button either runs a **server handler**
(`.handler(ctx -> ActionResult)` — POSTs to `/api/divkit/page-action`) or **navigates**
(`.navigate("onno://...")`).

A server handler runs only for an authenticated user, and `onno.ui.read-only` blocks it like every
other mutation. Because a page action has no entity to gate on, declare `.roles("MANAGER")` to
restrict who may run it — the endpoint rejects other callers **and the button is hidden from the
rendered page**; `ADMIN` always passes, like entity `@AccessControl`. Without `.roles(...)`, any
authenticated user may run it and the handler self-authorizes via `ctx.user()`.

```java
b.actions("Connected accounts", a ->
    a.action("connect-tochka")
     .label("Connect Tochka Bank")
     .logo("https://enter.tochka.com/favicon.ico")   // brand image, shown instead of a lucide icon
     .handler(ctx -> ActionResult.redirect(oauth.beginConnect("tochka"))));
```

Button face — set **one**:

| Builder | Renders |
|---------|---------|
| `.icon("download")` | a kebab-case [lucide](https://lucide.dev) icon |
| `.logo("https://…/x.svg")` | an image (URL or app-static path) — for brand marks like "Connect with X". Rendered on page-action and list/row/toolbar buttons. |

`ActionResult` (what a handler returns):

| Factory | Effect |
|---------|--------|
| `ok()` | acknowledge, nothing observable |
| `message(text)` | success toast |
| `refresh(text)` | toast + reload the current surface |
| `navigate("onno://…")` | route the client (internal `onno://` scheme; `{id}` is filled for row actions) |
| `redirect(url)` | **full-page** navigation of the top-level browser to an external `url` (e.g. an OAuth consent screen that redirects back) — emitted as the `onno://redirect/<url>` scheme |

`redirect(...)` differs from `navigate("onno://open/<url>")`, which opens a **new tab** (for viewing
files); `redirect` replaces the current page so a provider round-trip lands back in the app.

### Comments — `/api/comments`

A catalog or document detail surface can carry a **discussion thread**: a feed of authored,
timestamped comments with a compose box, rendered by the `onno-comments` DivKit panel. Comments are
framework infrastructure, not modelled entities — they live in the framework-owned `onno_comments`
table (created at startup, never shown in the nav), so any entity can get the feature with no
per-entity modelling. Each author's avatar resolves from the identity catalog's avatar-hinted
attribute (falling back to initials).

Comments are **opt-in per entity**, and off by default. An entity gets a thread only when its
`EntityView` turns it on — so you choose exactly which catalogs/documents support discussions:

```java
@Component
public class BookingView implements EntityView {
    @Override public Class<?> entity() { return Booking.class; }
    @Override public boolean comments() { return true; }   // off by default; opt in here
}
```

The opt-in is resolved at the entity level (if any of an entity's profile views opts in, its detail
carries the panel). An entity that hasn't opted in shows no panel, and its `/api/comments/...`
endpoints return **404** — the comment surface doesn't exist there.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/comments/{kind}/{name}/{id}` | The thread for one record, oldest first. `{kind}` is `catalogs`/`documents`. `404` if the entity hasn't opted into comments. Each comment carries `parentId` (null for top-level), `mentions` (live mention/reference resolution), and grouped `reactions` (`{ emoji, count, mine }`). |
| POST | `/api/comments/{kind}/{name}/{id}` | Add a comment or reply — body `{ "body": "…", "parentId": "…" }` (`parentId` optional/null). The author is stamped from the session ([CurrentUserResolver](src/main/java/su/onno/ui/CurrentUserResolver.java)); the client never asserts identity. |
| POST | `/api/comments/{commentId}/reactions` | Toggle the caller's reaction — body `{ "emoji": "👍" }`; returns the updated grouped reactions for that comment and live-syncs the thread. Supported quick reactions: `👍`, `❤️`, `🎉`, `👀`, `✅`. |
| DELETE | `/api/comments/{commentId}` | Soft-delete (kept for audit). Author or `ADMIN` only. |
| GET | `/api/mentions?q=&kind=` | Mention/reference typeahead: every matching record the caller can read, ranked and capped. `kind=catalogs` powers `@`; `kind=documents` powers `#`; omit it to search both. Returns `[{ kind, name, entity, id, display, avatarUrl }]`. |

Reading and posting are gated on **read** access to the owning entity (and on the entity's opt-in
above) — if you can open the record and the entity supports comments, you can comment on it.
`onno.comments.enabled=false` is the global kill switch (drops the endpoint, table, and panel
everywhere); `onno.comments.max-length` caps body length (default 4000).

The bundled comments panel renders the thread as a compact message scroller: current-user messages
align to the end, other authors keep their avatar on the start side, replies nest under their parent,
and reaction chips sit on the bubble edge.

#### Mentions and references — `@` people, `#` documents

A comment body can **`@`-mention** readable catalog records (including colleagues when users are
modelled as the identity catalog) and **`#`-reference** readable documents. Links reuse the same
`Ref<T>` philosophy as the rest of the framework: only the identity is stored and display/avatar are
resolved **live**, so renames and deletes stay correct on their own.

- **Storage.** A link is a token embedded in `Comment.body`: `@[Display](kind/name/id)` for mentions
  and `#[Display](kind/name/id)` for references. The body stays a single string, and `Display` is
  only a snapshot for fallback. ([`Mentions`](src/main/java/su/onno/ui/comments/Mentions.java) is the parser/serializer.)
- **Access control.** Mentions inherit the per-entity read gate. On **POST**, a mention the *author*
  can't read is stripped to plain text (no smuggling a link to a hidden record). On **read**, a
  mention the *viewer* can't read degrades to plain text instead of a clickable 403; one they can read
  resolves to its current display + avatar. ([`MentionResolver`](src/main/java/su/onno/ui/comments/MentionResolver.java) batches this per thread.)
- **Notifications.** Each readable `@` mention in a freshly posted comment publishes an
  [`EntityMentionedEvent`](src/main/java/su/onno/ui/comments/EntityMentionedEvent.java). The
  notifications feature (below) consumes it out of the box to notify the mentioned user; you can add
  further delivery (`onno-mail-starter`, etc.) by registering your own Spring `@EventListener`.
- **Config.** `onno.comments.mentions.enabled` (default true) gates the whole feature;
  `onno.comments.mentions.suggestion-limit` / `…per-entity-limit` cap the typeahead.

### Notifications — `/api/notifications`

A **per-user notification center**: a top-right bell with an unread badge that opens a timeline drawer
of updates concerning the signed-in user. Like comments and presence it is framework infrastructure —
rows live in the framework-owned `onno_notifications` table, never in a modelled entity — and it rides
the same live plumbing: a new notification pushes over a `notification` SSE event routed to the
recipient's open streams, relayed across nodes by the `ClusterEventBus`. The bell hides itself when the
feature is disabled.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/notifications[?unread&cursor]` | The caller's timeline, newest first, keyset-paginated: `{ items, nextCursor, hasMore, unreadCount }`. `?unread=true` restricts to unread; `?cursor=` resumes. Scoped to the caller — never another user's feed. |
| POST | `/api/notifications/{id}/read` | Mark one read; returns the fresh `{ unreadCount }`. |
| POST | `/api/notifications/read-all` | Mark every unread one read; returns `{ marked, unreadCount }`. |

**Producing notifications.** Any bean can raise one through the public API — this is the extension
point:

```java
notificationService.notify(NotificationRequest.to(recipientId)   // target's identity recordId
        .type("approval")
        .title("Your purchase was approved")
        .body("PO-1042 · €1,240")
        .link("documents/purchase_orders/" + id)   // opens this route on click
        .actor(currentUser)                        // who triggered it
        .build());
```

Add a new source by calling that from a Spring `@EventListener` (on `DocumentPostedEvent`, a domain
event, anything). Two producers ship built in, each independently gated:

- **Mentions** (`onno.notifications.mentions.enabled`, default true) — a comment `@`-mention of a user
  notifies that user.
- **Assignment** (`onno.notifications.assignments.enabled`, default true) — annotate a catalog/document
  `Ref<>` attribute that points at the identity catalog with **`@AssigneeField`**; setting or changing
  it notifies the assignee.

  ```java
  @AssigneeField
  @Attribute private Ref<Employee> assignedTo;   // assigning notifies the employee
  ```

**Config.** `onno.notifications.enabled=false` is the global kill switch (drops the endpoint, table,
producers, and bell). `onno.notifications.page-size` (default 30) sizes each timeline window;
`onno.notifications.retention-days` (default 90; `0` disables) prunes **read** notifications after that
many days — unread ones are kept indefinitely.

### Presence — `/api/presence`

**Ambient presence markers**: live collaborator avatars showing who else is viewing what, across the
whole app — like Notion. Presence is framework infrastructure with **no per-entity modelling and no
opt-in**, and surfaces in three places:

- **Tab bar** — the other viewers of the focused pane's record, pinned right of its tabs.
- **List rows** — a marker on each row whose record someone is viewing.
- **Sidebar** — a dot on each catalog/document nav item whose entity has active viewers.

**How it works.** Each open pane marks its record present by route — the client `POST`s `enter` on open,
a `heartbeat` every ~15s, and `leave` on close. A per-node in-memory
[`PresenceRegistry`](src/main/java/su/onno/ui/presence/PresenceRegistry.java) holds who is viewing what,
keyed by `{kind, name, id}`, and pushes the viewer set onto the SSE stream as a `presence` event whenever
it changes (a join or a leave — never on a bare heartbeat). A viewer is kept alive by heartbeats and
**expires by TTL** (~45s) once they stop, so a closed tab or a crashed node self-heals without relying on
the `leave` arriving. Identity is stamped from the session
([CurrentUserResolver](src/main/java/su/onno/ui/CurrentUserResolver.java)); the client never asserts who
it is, and the markers exclude the caller themselves.

**The client store.** All three surfaces read one client-wide store
(`src/main/frontend/src/lib/presence-store.ts`): seeded once from `GET /api/presence`, then kept current
by the `presence` deltas on the shared SSE fan-out. The snapshot is filtered to entities the caller may
read — you never learn that someone is viewing a record in an entity you can't open. (Live deltas are
broadcast app-wide, but only ever rendered on surfaces — rows, nav items — the caller already sees.) The
`presence` event carries `entityType: "presence"` (a sentinel, not the record's kind) so no list/detail
surface mistakes a ping for a row change.

**Across nodes.** Each change is relayed as a `Presence` `ClusterEvent` over the same
[`ClusterEventBus`](../onno-framework/src/main/java/su/onno/cluster/ClusterEventBus.java) that carries
live entity changes (default Postgres `LISTEN`/`NOTIFY` via `onno-cluster-starter`; in-memory single-node
otherwise), so a viewer on any node sees viewers on every node. Like all cluster traffic it is
best-effort — a dropped ping costs at most one TTL of staleness.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/presence` | The ambient snapshot: `{ you, records: [{ kind, name, id, viewers: [{ userId, displayName }] }] }` — every viewed record the caller may read. The client loads it once, then live `presence` SSE deltas keep its store current. |
| POST | `/api/presence/{kind}/{name}/{id}` | Mark presence on a record — body `{ "action": "enter" \| "heartbeat" \| "leave" }`. `{kind}` is `catalogs`/`documents`. Gated on read access to the entity (`403` otherwise); `404` for an unknown kind. Returns `{ you, viewers }`. |

### Misc — `/api`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/theme` | The `onno.ui.theme.*` map. |
| GET | `/api/config` | `{ readOnly, basePath, messages }` (the resolved chrome-string map — see [Localizing the chrome](#localizing-the-chrome)), plus `update: { available, current, latest, url }` when the update check is enabled. |
| GET | `/api/events` | Server-Sent Events stream of CRUD/posting changes and `presence` viewer-set updates (`text/event-stream`). The SPA shares **one** such connection across all tabs of an origin (leader elected via the Web Locks API, events rebroadcast over a `BroadcastChannel`), so many open tabs don't exhaust the browser's per-origin connection limit. |

> **There is no `/api/ui/metadata/manifest` endpoint** (no module serves it; the only `/manifest`
> route is the desktop shell's `/api/desktop/manifest`). To introspect the business model at runtime,
> use the generated endpoints above or the MCP `describe_metadata` tool from `onno-mcp-starter`.

## Access model — read this before calling the API

Security is configured by **`onno-auth-starter`** (its `SecurityFilterChain`), not here. The shape
that matters to an integrator:

- **The SPA is public; `/api/**` requires an authenticated session.** Everything outside `/api/**`
  (the SPA shell and static assets) is permitted anonymously so the login screen can load.
  `/api/**` is `authenticated()`, except the public endpoints `/api/theme`, `/api/config`,
  `/api/auth/login` (and `/error`, plus the desktop probes). Unauthenticated `/api/**` calls get
  `401` with `{"error":"unauthenticated"}` — **not** an HTTP Basic challenge (form login and Basic
  are both disabled).
- **Auth is a session cookie, established by a JSON login** — not Basic auth, not a bearer token.
- **Per-entity RBAC is deny-by-default.** A catalog/document/register is invisible and uneditable
  unless its read/write roles grant the caller, with one exception: the `ADMIN` role is a superuser
  that bypasses every per-entity check. Roles are matched case-insensitively with the `ROLE_`
  prefix stripped. Failing a check returns `403`.
- **Mutating requests need a CSRF token.** POST/PUT/DELETE must send the token from the
  `XSRF-TOKEN` cookie back in the `X-XSRF-TOKEN` header (Spring's `CookieCsrfTokenRepository`,
  non-HttpOnly so JS can read it). The cookie is issued on the first response thanks to a
  `CsrfCookieFilter`. `POST /api/auth/login` is **exempt** from CSRF so you can bootstrap a session.

### Auth endpoints (from onno-auth-starter)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/auth/login` | Body `{"username","password"}`. On success sets the session cookie and returns `{authenticated, username, roles}`; `401` on bad credentials, `400` on a malformed body. CSRF-exempt. |
| POST | `/api/auth/logout` | Invalidates the session. |
| GET | `/api/auth/me` | Current user, or `{authenticated:false,...}` when anonymous. |
| GET | `/api/auth/csrf` | This session's CSRF token `{token, headerName, parameterName}`, for clients that can't read the `XSRF-TOKEN` cookie (e.g. native mobile). Public; `token` is `null` in `resource-server` mode. |

### Logging in to call the API

```bash
# 1. Hit any public endpoint to receive the XSRF-TOKEN cookie, saving the cookie jar.
curl -c jar.txt http://localhost:8080/api/config

# 2. Log in (CSRF-exempt). The response sets the session cookie into the same jar.
curl -b jar.txt -c jar.txt -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"secret"}'

# 3. Read calls only need the session cookie.
curl -b jar.txt http://localhost:8080/api/catalogs/Properties

# 4. Mutating calls also need the CSRF token: read it from the cookie jar and
#    echo it back in the X-XSRF-TOKEN header.
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' jar.txt)
curl -b jar.txt -X POST http://localhost:8080/api/catalogs/Properties \
  -H "X-XSRF-TOKEN: $XSRF" \
  -H 'Content-Type: application/json' \
  -d '{"description":"Seaside Villa"}'
```

A browser SPA gets this for free: the cookie is sent automatically and the client copies
`XSRF-TOKEN` into the header on writes.

### Providing users

In-memory users come from `onno-auth-starter`. Add at least one (the `ADMIN` role makes it a
superuser for the UI):

```yaml
onno:
  auth:
    users:
      - username: admin
        password: secret
        roles: [ADMIN]
```

## SPA routing, the base path, and the index fallback

The SPA is mounted under `onno.ui.path` (default `/ui`). The server bakes that prefix into the
served `index.html` — replacing the `__ONNO_BASE_PATH__` placeholder with a `window.__onnoBasePath`
value — so the web client adopts it synchronously as its React Router `basename` ([base-path.ts](src/main/frontend/src/lib/base-path.ts))
before routing or any deep-link fetch happens. Because React Router strips the `basename` from
`useLocation().pathname`, in-app routes and the `/api/divkit{path}` calls they drive stay
prefix-relative while the browser URL carries the prefix (e.g. `/ui/catalogs/Properties`).

`SpaResourceResolver` (registered on `/**`) falls back to that injected `index.html` for any path
that isn't a real static asset, so client-side **deep links cold-load** straight onto their surface.
`SpaIndexController` serves the root: when a base path is configured it redirects `/` → the base
path (React Router renders nothing for a URL outside its `basename`, so the bare root must bounce
into it); when `onno.ui.path` is `/` it serves the shell directly. Assets (JS/CSS/icons) are served
from `classpath:/static/ui/` at the web root regardless of the base path, so they load at any route
depth.

> **Gotcha:** because of the fallback, an unknown path under the SPA returns `index.html` with
> **HTTP 200**, not a `404`. A mistyped non-`/api` URL looks "successful" and renders the SPA shell.
> Only `/api/**` paths produce real `404`/`401`/`403` responses. When debugging, test API URLs, not
> page URLs.
