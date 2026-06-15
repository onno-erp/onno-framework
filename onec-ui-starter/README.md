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
    path: /ui            # SPA base path advertised via /api/config
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
| `onec.ui.path` | `/ui` | SPA base path, returned to the client as `basePath` from `GET /api/config`. |
| `onec.ui.read-only` | `false` | When `true`, every mutating REST call (POST/PUT/DELETE and post/unpost) returns `403 UI is in read-only mode`. |
| `onec.ui.theme.*` | empty map | Free-form theme key/value pairs, served verbatim from `GET /api/theme`. |

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

### List row actions

Every list row (DivKit-rendered lists and the virtualized `EntityListWidget` alike) supports:

- **Right-click** → a context menu with **Open**, **Edit**, **Duplicate** and **Delete**.
- **Delete key** (macOS **fn+Backspace**, or the dedicated Del key) → deletes the row **under the
  pointer** — the one the hover highlight marks. Ignored while typing in a field or while a menu or
  dialog is open.

Both delete paths open the in-app confirmation dialog and then issue `DELETE /api/{kind}/{name}/{id}`
(soft delete), so the server still enforces write access — a read-only user (or one without the
entity's write role) gets a `403`, never a silent delete.

## Dashboard widgets

Widgets are authored on a `Page` (or `layout.widget(...)`) with the `WidgetBuilder` DSL and
compiled to DivKit. `count`/`metric` render as native big-number cards (resolved server-side);
every other type — the built-in `chart`/`calendar`/`kanban`/`list` and any app-registered type —
is emitted as an `onec-widget` custom block that the client renders with a React component.

```java
b.widget("Revenue").type("metric").width("1/4").document(Bill.class)
 .config("metric", "sum").config("metricField", "gross").config("currency", "EUR");
```

### Widget types

| `type(...)` | Renders | Notes |
|-------------|---------|-------|
| `count` | KPI card — row count | Honours `filter`. |
| `metric` | KPI card — aggregated value | `metric` = `sum`/`avg`/`min`/`max` over `metricField`; honours `filter`, `currency`/`format`. Works on `document`, `catalog`, or a register resource. |
| `chart` | recharts bar/line/area/donut/**pie** | Source from `document`/`catalog` rows or a `register` (server-side turnover). |
| `calendar` | FullCalendar | Documents only; drag-to-reschedule. |
| `list` | Recent-records list | Configurable title/secondary/amount/date. |
| `kanban` | Drag board grouped by a field | |
| *(custom)* | App-registered React component | Register on the client with `registerWidget("gauge", GaugeWidget)`. |

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
| `kind` | chart | `bar`/`line`/`area`/`donut`/`pie`. Unknown kinds warn and fall back to `bar`. |
| `groupBy`, `groupByDate` | chart | Bucket field, and `day`/`week`/`month` for date buckets. |
| `titleTemplate` | list | `"{guest_name} — {property_display}"`; unknown fields render empty. |
| `secondaryField` | list, calendar | Comma-list of fields for the second line (first non-empty wins). |
| `amountField` | list, calendar | Column for the trailing money figure (defaults to `total`/`gross`-style fields). |
| `dateField` | list, calendar | Column for the date (also `.dateField(...)` on the builder). |

> A register-backed `metric`/`chart` sums a register **resource** over its turnover; `metricField`
> must name a resource column, and `filter` may reference its **dimensions**.

### Registering a custom widget (client)

```ts
import { registerWidget } from "@/lib/widget-bridge";
registerWidget("gauge", GaugeWidget); // server: b.widget("SLA").type("gauge").document(Incident.class)
```

The server emits any non-native `type(...)` as an `onec-widget` descriptor; an unregistered type
renders a labelled placeholder rather than vanishing.

### Misc — `/api`

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/theme` | The `onec.ui.theme.*` map. |
| GET | `/api/config` | `{ readOnly, basePath }`. |
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

## SPA routing and the index fallback

`SpaIndexController` serves `static/ui/index.html` at `/`, and `SpaResourceResolver` (registered on
`/**`) falls back to `index.html` for any path that doesn't match a real static asset, so React
Router can handle client-side deep links.

> **Gotcha:** because of this fallback, an unknown path under the SPA returns `index.html` with
> **HTTP 200**, not a `404`. A mistyped non-`/api` URL looks "successful" and renders the SPA shell.
> Only `/api/**` paths produce real `404`/`401`/`403` responses. When debugging, test API URLs, not
> page URLs.
