# onec-mcp-starter

Exposes an onec application's business model to LLM clients over the
[Model Context Protocol](https://modelcontextprotocol.io) (MCP).

Because onec is metadata-driven, the tools are **generated generically** from the
`MetadataRegistry` — there is no per-entity code. Adding a `@Catalog`, `@Document`, or
`@AccumulationRegister` to your app makes it reachable over MCP automatically, and a
`describe_metadata` discovery tool lets the model learn the exact entity, field, and enum
names at runtime.

Every tool call runs **as the authenticated user** and is enforced through the same
`UiAccessService` deny-by-default role model as the web UI. The LLM is just another
caller — it never gains access a user role wouldn't have.

## What it adds

A streamable-HTTP MCP endpoint (default `/mcp`) backed by the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), and an HTTP Basic
security filter chain scoped to that endpoint that reuses your existing
`UserDetailsService`/`AuthenticationManager` (e.g. from `onec-auth-starter`).

### Tools

| Tool | Kind | Purpose |
| --- | --- | --- |
| `describe_metadata` | read | List readable catalogs/documents/registers with fields and enum values. |
| `list_catalog`, `get_catalog` | read | Query catalog records (search or full list; single record). |
| `list_documents`, `get_document` | read | Query documents (optional date range; single document with tabular sections). |
| `register_balance`, `register_movements` | read | Accumulation register balances and movements. |
| `create_catalog`, `update_catalog`, `delete_catalog` | write | Create/update/soft-delete catalog records. *(gated by `onec.mcp.writes-enabled`)* |
| `create_document`, `update_document`, `delete_document` | write | Create/update/soft-delete documents. A posted document is unposted before delete. *(gated by `onec.mcp.writes-enabled`)* |
| `posting_preview` | read | Show the register movements a document would make if posted. *(gated by `onec.mcp.posting-enabled`)* |
| `post_document`, `unpost_document` | write | Post/unpost a document to registers. **Has ledger side-effects.** *(gated by `onec.mcp.posting-enabled`)* |

## Usage

Add the dependency (alongside `onec-ui-starter` and a security provider such as
`onec-auth-starter`):

```kotlin
dependencies {
    implementation("com.onec:onec-mcp-starter:0.1.0")
}
```

It auto-configures when a `MetadataRegistry` bean and Spring Security are present.

### Configuration

| Property | Default | Description |
| --- | --- | --- |
| `onec.mcp.enabled` | `true` | Master switch. |
| `onec.mcp.endpoint` | `/mcp` | Servlet path of the MCP transport. |
| `onec.mcp.writes-enabled` | `true` | Expose create/update tools. |
| `onec.mcp.posting-enabled` | `true` | Expose post/unpost/preview tools. |
| `onec.mcp.server-name` | `onec` | Name advertised in the MCP handshake. |
| `onec.mcp.server-version` | `0.1.0` | Version advertised in the handshake. |
| `onec.mcp.instructions` | _(generated)_ | Client-facing usage instructions. |

### Connecting a client

Point an MCP client at `http(s)://<host>/mcp` using **streamable HTTP** transport and
HTTP Basic credentials for an application user. The user's roles determine which entities
and operations are visible.

## Security notes

- Identity is bridged from Spring Security into the tool call by `McpPrincipalContext`:
  the principal is captured on the servlet request thread (where the security filter chain
  has populated the context) and carried into the tool handler via the MCP transport
  context — so authorization survives the SDK's reactive hop.
- A `null`/anonymous principal is denied everything (deny by default).
- The `/mcp` chain is stateless and CSRF-exempt; clients authenticate per request.
- Posting tools mutate ledgers. Disable them with `onec.mcp.posting-enabled=false` if you
  want a read/edit-only surface.
