# onno-mcp-starter

Exposes an onno application's business model to LLM clients over the
[Model Context Protocol](https://modelcontextprotocol.io) (MCP).

Because onno is metadata-driven, the tools are **generated generically** from the
`MetadataRegistry` — there is no per-entity code. Adding a `@Catalog`, `@Document`, or
`@AccumulationRegister` to your app makes it reachable over MCP automatically, and a
`describe_metadata` discovery tool lets the model learn the exact entity, field, enum, and latest
typed process-graph names at runtime.

Every tool call runs **as the authenticated user** and is enforced through the same
`UiAccessService` deny-by-default role model as the web UI. The LLM is just another
caller — it never gains access a user role wouldn't have.

## What it adds

A streamable-HTTP MCP endpoint (default `/mcp`) backed by the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), and an HTTP Basic
security filter chain scoped to that endpoint that reuses your existing
`UserDetailsService`/`AuthenticationManager` (e.g. from `onno-auth-starter`).

### Tools

| Tool | Kind | Purpose |
| --- | --- | --- |
| `describe_metadata` | read | List readable catalogs/documents/registers plus versioned typed process graph descriptors, fields, and enum values. |
| `list_catalog`, `get_catalog` | read | Query catalog records (search or full list; single record). |
| `list_documents`, `get_document` | read | Query documents (optional date range; single document with tabular sections). |
| `register_balance`, `register_movements` | read | Accumulation register balances and movements. |
| `create_catalog`, `update_catalog`, `delete_catalog` | write | Create/update/soft-delete catalog records. *(gated by `onno.mcp.writes-enabled`)* |
| `create_document`, `update_document`, `delete_document` | write | Create/update/soft-delete documents. A posted document is unposted before delete. *(gated by `onno.mcp.writes-enabled`)* |
| `posting_preview` | read | Show the register movements a document would make if posted. *(gated by `onno.mcp.posting-enabled`)* |
| `post_document`, `unpost_document` | write | Post/unpost a document to registers. **Has ledger side-effects.** *(gated by `onno.mcp.posting-enabled`)* |

## Usage

Add the dependency (alongside `onno-ui-starter` and a security provider such as
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

### Connecting a client

Point an MCP client at `http(s)://<host>/mcp` using **streamable HTTP** transport and
HTTP Basic credentials for an application user. The user's roles determine which entities
and operations are visible.

### Custom tools

Add `@McpTool` to a public instance method on any Spring bean. Ordinary parameters become
JSON-schema inputs and are converted to their declared Java types. `Principal`,
`McpToolContext`, and `McpSyncServerExchange` parameters are injected and do not appear in the
schema.

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
  context — so authorization survives the SDK's reactive hop.
- A `null`/anonymous principal is denied everything (deny by default).
- The `/mcp` chain is stateless and CSRF-exempt; clients authenticate per request.
- Posting tools mutate ledgers. Disable them with `onno.mcp.posting-enabled=false` if you
  want a read/edit-only surface.
