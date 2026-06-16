# onec-ui-starter

Spring Boot starter that serves the onec admin / back-office UI. It bundles a React SPA (built with
Node 20 by Gradle) and exposes a generic REST + **DivKit** (server-driven UI) layer over the
business model: catalogs, documents and registers get list/detail/form screens generated from
metadata, with no per-entity controller to write.

The same DivKit contract drives the React web client today and is intended to drive a native
(Flutter) client later, so screen layout, RBAC and theming are resolved server-side.

## Enabling

Auto-configuration kicks in when `onec-ui-starter` is on the classpath, a `MetadataRegistry` bean
exists (contributed by `onec-framework-starter`), and the UI is enabled (the default):

```yaml
onec:
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
| `onec.ui.enabled` | `true` | Master switch. Also gated on a `MetadataRegistry` bean being present. |
| `onec.ui.path` | `/ui` | URL prefix the SPA is mounted under. Baked into the served `index.html` (and returned as `basePath` from `GET /api/config`) so the client uses it as its React Router `basename` and deep-link prefix; the bare root redirects here. Set to `/` to mount at the web root. |
| `onec.ui.read-only` | `false` | When `true`, every mutating REST call (POST/PUT/DELETE and post/unpost) returns `403 UI is in read-only mode`. |
| `onec.ui.settings.enabled` | `false` | Opt-in switch for the built-in Settings page (the `@Constant` editor) and its auto-injected admin nav entry. Off by default; an app that wants app-wide settings turns it on (or authors its own `Page` at `/settings`). |
| `onec.ui.theme.*` | empty map | Free-form theme key/value pairs, served verbatim from `GET /api/theme`. |
| `onec.ui.update-check.enabled` | `true` | Poll onec-cloud for a newer framework release and show an "update available" notice. Fail-silent; set `false` to disable all outbound checks. |
| `onec.ui.update-check.url` | `https://cloud.onno.su/releases/v1/latest` | Release-announcement endpoint to poll. |
| `onec.ui.update-check.interval` | `24h` | Cadence after the first check (floored at 60s). |
| `onec.ui.update-check.initial-delay` | `1m` | Delay before the first check. |

### Update-available notice

When `onec.ui.update-check.enabled` is on (the default), `UpdateChecker` polls
`onec.ui.update-check.url` on a background daemon thread and compares the announced version to the
running framework version (baked into `META-INF/onec-build.properties` at build time). The result
rides along on `GET /api/config` as an `update` object — `{ available, current, latest, url }` — and
the web client raises a dismissible toast when `available` is true. Every failure (offline, non-200,
unparseable body, unknown local version) is swallowed, so a flaky network never produces a wrong or
alarming notice. The endpoint it polls is served by onec-cloud (`GET /releases/v1/latest`), which
returns `204` when no release is announced.

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
| DELETE | `/{name}/{id}` | Soft delete (auto-unposts first if posted). |

### Registers — `/api/registers`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/{name}/movements?from=&to=` | Raw movements. |
| GET | `/{name}/balance?{dim}={value}...` | Current balances (query params are dimension filters). |
| GET | `/{name}/turnover?from=&to=&{dim}=...` | Period turnover; `from` and `to` are required. |

### DivKit server-driven UI — `/api/divkit`

These return DivKit card JSON resolved for the caller's persona/roles, theme (`?theme=`) and
viewport (`?viewport=`). `GET /shell` is the fast chrome (top bar + nav, no data); the rest are
data-bearing surfaces.

| Path | Surface |
|------|---------|
| `GET /shell` | Nav + account chrome. |
| `GET /home` | Dashboard / authored home page. |
| `GET /account`, `GET /menu` | Mobile account card and "More" nav hub. |
| `GET /catalogs/{name}`, `/catalogs/{name}/{id}`, `/catalogs/{name}/new`, `/catalogs/{name}/{id}/edit` | Catalog list, detail, create and edit forms. |
| `GET /documents/{name}`, `/documents/{name}/{id}`, `/documents/{name}/new`, `/documents/{name}/{id}/edit` | Document list, detail, create and edit forms. |
| `GET /registers/{name}` | Register report (balances for BALANCE registers, movements otherwise). |

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

- **Right-click** → a context menu with **Open**, **Edit**, **Duplicate**, **Copy link** and
  **Delete**. **Copy link** puts the row's shareable deep link (`{origin}/{kind}/{name}/{id}`) on the
  clipboard — paste it into a new tab to land straight on that record.
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

Each function receives an `ActionRow` — a read-only view of the row the list already rendered
(`id()`, `text(col)` for the display value, `enumValue(col, Type)` to read an enum column back, or
`get(col)`/`values()` for the raw map). They're evaluated **server-side per row** as the list page
is served (no extra query — the row is already in hand) and shipped to the grid under each row's
`_actions`; the button falls back to the static `icon`/`label` when a function isn't set. Per-row
functions apply to `ROW` actions only — toolbar/detail buttons have no row context and use the fixed
icon/label. A static row action (no functions) costs nothing: the list ships its rows untouched.

## Dashboard widgets

Widgets are authored on a `Page` (or `layout.widget(...)`) with the `WidgetBuilder` DSL and
compiled to DivKit. `count`/`metric` render as native big-number cards (resolved server-side);
every other type — the built-in `chart`/`stat`/`sparkline`/`gauge`/`calendar`/`kanban`/`list` and
any app-registered type — is emitted as an `onec-widget` custom block that the client renders with
a React component.

```java
b.widget("Revenue").type("metric").width("1/4").document(Bill.class)
 .config("metric", "sum").config("metricField", "gross").config("currency", "EUR");
```

### Widget types

| `type(...)` | Renders | Notes |
|-------------|---------|-------|
| `count` | KPI card — row count | Honours `filter`. |
| `metric` | KPI card — aggregated value | `metric` = `sum`/`avg`/`min`/`max` over `metricField`; honours `filter`, `currency`/`format`. Works on `document`, `catalog`, or a register resource. |
| `chart` | recharts bar/line/area/donut/**pie**, single- **or multi-series** | Source from `document`/`catalog` rows or a `register` (server-side turnover). `seriesBy` splits into colored series; `stacked` stacks them. |
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
| `filter` | count, metric | Safe predicate, e.g. `status != cancelled AND _posted = true`. Columns are validated; values are always bound (never inlined) — see `WidgetFilter`. |
| `currency` | metric, list, calendar, chart | ISO code (e.g. `EUR`) → currency formatting. |
| `format` | metric, list, calendar, chart | `integer` / `decimal` fraction-digit policy when not a currency. |
| `locale` | metric, list, calendar, chart | BCP-47 locale for number/currency grouping. |
| `currencyField` | list, calendar | Per-row column holding a currency code (overridden by `currency`). |
| `kind` | chart | `bar`/`line`/`area`/`donut`/`pie`. Unknown kinds warn and fall back to `bar`. For `stat`/`sparkline` it picks the sparkline shape (`area` default, or `line`). |
| `groupBy`, `groupByDate` | chart, stat, sparkline | Bucket field, and `day`/`week`/`month` for date buckets (date buckets are ordered chronologically). |
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

### Registering a custom widget (client)

```ts
import { registerWidget } from "@/lib/widget-bridge";
registerWidget("heatmap", HeatmapWidget); // server: b.widget("Load").type("heatmap").document(Shift.class)
```

The server emits any non-native `type(...)` as an `onec-widget` descriptor; an unregistered type
renders a labelled placeholder rather than vanishing.

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

## Page action buttons

`PageBuilder.actions(heading, spec)` adds a section of buttons to a page (the same `ActionSpec` an
`EntityView.actions(...)` uses for list/row buttons). Each button either runs a **server handler**
(`.handler(ctx -> ActionResult)` — POSTs to `/api/divkit/page-action`, runs for an authenticated
user, self-authorizes via `ctx.user()`) or **navigates** (`.navigate("onec://...")`).

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
| `navigate("onec://…")` | route the client (internal `onec://` scheme; `{id}` is filled for row actions) |
| `redirect(url)` | **full-page** navigation of the top-level browser to an external `url` (e.g. an OAuth consent screen that redirects back) — emitted as the `onec://redirect/<url>` scheme |

`redirect(...)` differs from `navigate("onec://open/<url>")`, which opens a **new tab** (for viewing
files); `redirect` replaces the current page so a provider round-trip lands back in the app.

### Comments — `/api/comments`

A catalog or document detail surface can carry a **discussion thread**: a feed of authored,
timestamped comments with a compose box, rendered by the `onec-comments` DivKit panel. Comments are
framework infrastructure, not modelled entities — they live in the framework-owned `onec_comments`
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
| GET | `/api/comments/{kind}/{name}/{id}` | The thread for one record, oldest first. `{kind}` is `catalogs`/`documents`. `404` if the entity hasn't opted into comments. Each comment carries a `mentions` array — the live resolution of its body's mentions for the caller. |
| POST | `/api/comments/{kind}/{name}/{id}` | Add a comment — body `{ "body": "…" }`. The author is stamped from the session ([CurrentUserResolver](src/main/java/com/onec/ui/CurrentUserResolver.java)); the client never asserts identity. |
| DELETE | `/api/comments/{commentId}` | Soft-delete (kept for audit). Author or `ADMIN` only. |
| GET | `/api/mentions?q=` | `@`-mention typeahead: every catalog/document record the caller can read whose code/description/number matches `q`, ranked and capped. Returns `[{ kind, name, entity, id, display, avatarUrl }]`. |

Reading and posting are gated on **read** access to the owning entity (and on the entity's opt-in
above) — if you can open the record and the entity supports comments, you can comment on it.
`onec.comments.enabled=false` is the global kill switch (drops the endpoint, table, and panel
everywhere); `onec.comments.max-length` caps body length (default 4000).

#### Mentions — `@`-reference any readable entity

A comment body can **`@`-mention** any catalog or document the author can read — a customer, an
invoice, or (since users are modelled as the identity catalog) a colleague. Mentions reuse the same
`Ref<T>` philosophy as the rest of the framework: only the identity is stored and display/avatar are
resolved **live**, so renames and deletes stay correct on their own.

- **Storage.** A mention is a token embedded in `Comment.body`: `@[Display](kind/name/id)`. The body
  stays a single string — no `onec_comments` schema change — and the `Display` is only a snapshot for
  fallback. ([`Mentions`](src/main/java/com/onec/ui/comments/Mentions.java) is the parser/serializer.)
- **Access control.** Mentions inherit the per-entity read gate. On **POST**, a mention the *author*
  can't read is stripped to plain text (no smuggling a link to a hidden record). On **read**, a
  mention the *viewer* can't read degrades to plain text instead of a clickable 403; one they can read
  resolves to its current display + avatar. ([`MentionResolver`](src/main/java/com/onec/ui/comments/MentionResolver.java) batches this per thread.)
- **Notifications (additive).** Each readable mention in a freshly posted comment publishes an
  [`EntityMentionedEvent`](src/main/java/com/onec/ui/comments/EntityMentionedEvent.java) — **no
  consumers** ship with the framework. Wire delivery (in-app, the cross-node event bus, `onec-mail-starter`)
  later by registering a Spring `@EventListener`, exactly as you would for `DocumentPostedEvent`.
- **Config.** `onec.comments.mentions.enabled` (default true) gates the whole feature;
  `onec.comments.mentions.suggestion-limit` / `…per-entity-limit` cap the typeahead.

### Misc — `/api`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/theme` | The `onec.ui.theme.*` map. |
| GET | `/api/config` | `{ readOnly, basePath }`, plus `update: { available, current, latest, url }` when the update check is enabled. |
| GET | `/api/events` | Server-Sent Events stream of CRUD/posting changes (`text/event-stream`). |

> **There is no `/api/ui/metadata/manifest` endpoint** (no module serves it; the only `/manifest`
> route is the desktop shell's `/api/desktop/manifest`). To introspect the business model at runtime,
> use the generated endpoints above or the MCP `describe_metadata` tool from `onec-mcp-starter`.

## Access model — read this before calling the API

Security is configured by **`onec-auth-starter`** (its `SecurityFilterChain`), not here. The shape
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

### Auth endpoints (from onec-auth-starter)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/auth/login` | Body `{"username","password"}`. On success sets the session cookie and returns `{authenticated, username, roles}`; `401` on bad credentials, `400` on a malformed body. CSRF-exempt. |
| POST | `/api/auth/logout` | Invalidates the session. |
| GET | `/api/auth/me` | Current user, or `{authenticated:false,...}` when anonymous. |

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

In-memory users come from `onec-auth-starter`. Add at least one (the `ADMIN` role makes it a
superuser for the UI):

```yaml
onec:
  auth:
    users:
      - username: admin
        password: secret
        roles: [ADMIN]
```

## SPA routing, the base path, and the index fallback

The SPA is mounted under `onec.ui.path` (default `/ui`). The server bakes that prefix into the
served `index.html` — replacing the `__ONEC_BASE_PATH__` placeholder with a `window.__onecBasePath`
value — so the web client adopts it synchronously as its React Router `basename` ([base-path.ts](src/main/frontend/src/lib/base-path.ts))
before routing or any deep-link fetch happens. Because React Router strips the `basename` from
`useLocation().pathname`, in-app routes and the `/api/divkit{path}` calls they drive stay
prefix-relative while the browser URL carries the prefix (e.g. `/ui/catalogs/Properties`).

`SpaResourceResolver` (registered on `/**`) falls back to that injected `index.html` for any path
that isn't a real static asset, so client-side **deep links cold-load** straight onto their surface.
`SpaIndexController` serves the root: when a base path is configured it redirects `/` → the base
path (React Router renders nothing for a URL outside its `basename`, so the bare root must bounce
into it); when `onec.ui.path` is `/` it serves the shell directly. Assets (JS/CSS/icons) are served
from `classpath:/static/ui/` at the web root regardless of the base path, so they load at any route
depth.

> **Gotcha:** because of the fallback, an unknown path under the SPA returns `index.html` with
> **HTTP 200**, not a `404`. A mistyped non-`/api` URL looks "successful" and renders the SPA shell.
> Only `/api/**` paths produce real `404`/`401`/`403` responses. When debugging, test API URLs, not
> page URLs.
