# onno-mcp-starter

Exposes an onno application's business model to LLM clients over the
[Model Context Protocol](https://modelcontextprotocol.io) (MCP).

Because onno is metadata-driven, the tools are **generated generically** from the
`MetadataRegistry` — there is no per-entity code. Adding a `@Catalog`, `@Document`, or
`@AccumulationRegister` to your app makes it reachable over MCP automatically, and a
`describe_metadata` discovery tool lets the model learn the exact entity, field, enum, and latest
typed process-graph names at runtime.

Every tool call runs **as the authenticated user** and is enforced through the same
`AccessService` deny-by-default role model as the web UI — and inside the same record scope
(`RecordAccessPolicy`): a tool never returns or changes a record the user couldn't open in the UI.
The LLM is just another caller — it never gains access a user role wouldn't have.

## What it adds

A streamable-HTTP MCP endpoint (default `/mcp`) backed by the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), and an HTTP Basic
security filter chain scoped to that endpoint that reuses your existing
`UserDetailsService`/`AuthenticationManager` (e.g. from `onno-auth-starter`).

### Tools

| Tool | Kind | Purpose |
| --- | --- | --- |
| `describe_metadata` | read | List readable catalogs/documents/registers plus versioned typed process graph descriptors, fields, and enum values. |
| `list_catalog`, `get_catalog` | read | Keyset-page/search catalog records (`rows`, opaque `nextCursor`, `hasMore`); get one record. |
| `list_documents`, `get_document` | read | Keyset-page/search documents with optional date range; get one document with tabular sections. |
| `register_balance`, `register_movements` | read | Accumulation register balances and movements. Results are capped at 5,000 and 1,000 rows respectively; a wider match returns an MCP error instead of a silent partial list, so narrow dimension filters or the movement period. |
| `create_catalog`, `update_catalog`, `delete_catalog` | write | Create/update/soft-delete catalog records. *(gated by `onno.mcp.writes-enabled`)* |
| `create_document`, `update_document`, `delete_document` | write | Create/update/soft-delete documents. A posted document is unposted before delete. *(gated by `onno.mcp.writes-enabled`)* |
| `posting_preview` | read | Show the register movements a document would make if posted. *(gated by `onno.mcp.posting-enabled`)* |
| `period_close_status` | read | Closed accounting periods (scope, inclusive `closedThrough`, `soft`/`hard`), so an agent knows a backdated write will be rejected. Registered when period close is enabled. |
| `post_document`, `unpost_document` | write | Post/unpost a document to registers. **Has ledger side-effects.** Honours the document's `@AccessControl(postRoles/unpostRoles)` like the REST API. *(gated by `onno.mcp.posting-enabled`)* |
| `<entity>_<command>` | write | One tool per command of a `Commands<E>` bean (see below). *(gated by `onno.mcp.writes-enabled`)* |

## Usage

Add the dependency (alongside `onno-web-starter` and a security provider such as
`onno-auth-starter`):

```kotlin
dependencies {
    implementation("su.onno:onno-mcp-starter:0.1.0")
}
```

It auto-configures when a `MetadataRegistry` bean and Spring Security are present.

### Configuration

| Property | Default | Description |
| --- | --- | --- |
| `onno.mcp.enabled` | `true` | Master switch. |
| `onno.mcp.endpoint` | `/mcp` | Servlet path of the MCP transport. |
| `onno.mcp.writes-enabled` | `true` | Expose create/update tools. |
| `onno.mcp.posting-enabled` | `true` | Expose post/unpost/preview tools. |
| `onno.mcp.server-name` | `onno` | Name advertised in the MCP handshake. |
| `onno.mcp.server-version` | `0.1.0` | Version advertised in the handshake. |
| `onno.mcp.instructions` | _(generated)_ | Client-facing usage instructions. |

### Commands as tools

Every command an application declares with a `Commands<E>` bean (`su.onno.command`) is also an MCP
tool, so an agent can do what a user does under the same rules. The tool is named `<entity>_<key>`
(restricted to `[A-Za-z0-9_.-]`, at most 64 characters), described by the command's `description`
(else its title), and takes one property per input (a number, a date, an enum of options or a
referenced record's UUID), an array of objects per row group, and the record `id` for a record
command. A call runs through the web starter's `CommandService` as the caller: entity write access,
the command's roles, read-only mode, record scope and `availableWhen`/`enabledWhen` apply exactly as on
`/api/commands`. The result is the command's feedback; a `CommandRejectedException` comes back as a
tool error. Commands declared with `c.dynamic(...)` change at runtime and are not published as tools.

### Connecting a client

Point an MCP client at `http(s)://<host>/mcp` using **streamable HTTP** transport and
HTTP Basic credentials for an application user. The user's roles determine which entities
and operations are visible.

### Custom tools

Add `@McpTool` to a public instance method on any Spring bean. Ordinary parameters become
JSON-schema inputs and are converted to their declared Java types. `Principal`, `AccessSubject`,
`McpToolContext`, and `McpSyncServerExchange` parameters are injected and do not appear in the
schema. A tool that reads or changes records on the caller's behalf passes the `AccessSubject`
(also `McpToolContext.subject()`) to the web starter's query services or checks with `RecordAccess` —
typed repositories are not record-scoped.

```java
@Component
class ShippingTools {

    @McpTool(
        name = "quote_shipping",
        title = "Quote shipping",
        description = "Returns a shipping quote for the current user",
        roles = "SALES",
        readOnly = true
    )
    public ShippingQuote quote(
            @McpToolParam(description = "Destination postal code") String postalCode,
            @McpToolParam(required = false) Optional<ShippingSpeed> speed,
            Principal caller) {
        return shipping.quote(postalCode, speed.orElse(ShippingSpeed.STANDARD), caller.getName());
    }
}
```

`@McpToolParam` can override the input name, description, required flag, or its complete JSON
Schema. Scalars, enums, arrays, collections, maps, and object parameters receive a basic inferred
schema. `@McpTool` also exposes MCP's `readOnly`, `destructive`, `idempotent`, `openWorld`, and
optional output-schema metadata.

Custom tools are additive to the generated onno tools. Tool names must be unique across all
contributors; duplicates fail startup. Calls require an authenticated user, and `roles` is an
any-of allowlist with `ADMIN` as superuser. A custom tool with `readOnly = false` is omitted when
`onno.mcp.writes-enabled=false`.

For an SDK-level tool, register an `McpToolProvider` bean returning
`SyncToolSpecification` values. Applications no longer need to replace `MetadataToolFactory` to
add tools.

## Security notes

- Identity is bridged from Spring Security into the tool call by `McpPrincipalContext`:
  the principal is captured on the servlet request thread (where the security filter chain
  has populated the context) and carried into the tool handler via the MCP transport
  context — so authorization survives the SDK's reactive hop. The caller's record-scoped
  `AccessSubject` is resolved there too and travels the same way.
- A `null`/anonymous principal is denied everything (deny by default).
- The `/mcp` chain is stateless and CSRF-exempt; clients authenticate per request.
- Posting tools mutate ledgers. Disable them with `onno.mcp.posting-enabled=false` if you
  want a read/edit-only surface.
