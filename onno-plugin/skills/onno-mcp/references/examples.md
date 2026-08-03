# MCP Examples

## Table Of Contents

- Dependency And Config
- Client Connection
- Agent Workflow
- Safety Gates
- Custom Tools
- Debugging

## Dependency And Config

```kotlin
dependencies {
    implementation("su.onno:onno-framework-starter:$onnoVersion")
    implementation("su.onno:onno-ui-starter:$onnoVersion")
    implementation("su.onno:onno-auth-starter:$onnoVersion")
    implementation("su.onno:onno-mcp-starter:$onnoVersion")
}
```

```yaml
onno:
  auth:
    mode: in-memory
    users:
      - username: mcp-reader
        password: "${MCP_READER_PASSWORD}"
        roles: [REPORTER]
  mcp:
    enabled: true
    endpoint: /mcp
    writes-enabled: false
    posting-enabled: false
    server-name: acme-onno
```

Start read-only. Enable mutations only after authentication, RBAC, metadata, and disposable-record
verification. HTTP Basic credentials come from the application's `UserDetailsService`; the example
above provisions one in-memory user.

```yaml
onno:
  mcp:
    writes-enabled: false
    posting-enabled: false
```

## Client Connection

Point the MCP client at:

```text
http://localhost:8080/mcp
```

Use streamable HTTP transport with HTTP Basic credentials for an application user. The user's roles
determine the metadata and operations the tools expose.

## Agent Workflow

1. Call `describe_metadata`.
2. Use the returned exact entity names, field names, enum values, and writable/readable capabilities.
3. Read records before changing them.
4. Use create/update tools for drafts.
5. Use `posting_preview` before `post_document` for documents with register effects.
6. Summarize mutations and resulting register movements.

Do not guess route/entity names from Java classes. Use metadata.

## Safety Gates

- `onno.mcp.writes-enabled=false` removes create/update/delete tools.
- `onno.mcp.posting-enabled=false` removes post/unpost/preview tools.
- Entity `@AccessControl` still gates every operation.
- `onno.ui.read-only=true` independently blocks mutation services even if MCP write tools exist.
- The HTTP chain rejects anonymous requests. Direct/internal anonymous metadata calls hide
  RBAC-controlled entities; enum metadata is not a substitute for authenticated entity access.

## Tool Contracts

| Tool | Required inputs | Optional inputs / result |
| --- | --- | --- |
| `describe_metadata` | — | `kind=catalog|document|register|process|all` |
| `list_catalog` | `name` | `query`, opaque `cursor`, `limit`; returns `{rows,nextCursor,hasMore}` |
| `list_documents` | `name` | `query`, opaque `cursor`, `limit`, ISO `from`/`to`; same envelope |
| `get_catalog`, `get_document` | `name`, UUID `id` | one read-shaped row |
| create tools | `name`, `values` | write fields use model names; Ref/enum values are UUID strings |
| update tools | `name`, UUID `id`, `values` | partial update |
| delete/post/unpost/preview | `name`, UUID `id` | command result/preview |
| `register_balance` | `name` | dimension `filters` |
| `register_movements` | `name` | ISO `from`/`to` |

## Custom Tools

```java
@Component
class ShippingTools {
    @McpTool(name = "quote_shipping", description = "Quote a shipment", roles = "SALES")
    public ShippingQuote quote(
            @McpToolParam(description = "Destination postal code") String postalCode,
            Principal caller) {
        return shipping.quote(postalCode, caller.getName());
    }
}
```

Ordinary parameters become typed inputs. `Principal`, `McpToolContext`, and
`McpSyncServerExchange` are injected. For mutations set `readOnly = false`; those tools disappear
when `onno.mcp.writes-enabled=false`. Use an `McpToolProvider` bean for direct SDK specifications.

## Debugging

- If `describe_metadata` is empty, check user roles and `@AccessControl`.
- If writes are missing, check `onno.mcp.writes-enabled`.
- If posting tools are missing, check `onno.mcp.posting-enabled`.
- If tools exist but writes return `403`, check `onno.ui.read-only` and entity write roles.
- If auth fails, remember `/mcp` is stateless and authenticates per request.
- If a tool sees stale metadata, restart the app after changing model classes.

## Safe Smoke Flow

1. Keep writes/posting disabled and confirm unauthenticated `/mcp` returns `401`.
2. Connect a streamable-HTTP client with Basic auth; initialize and list tools.
3. Call `describe_metadata`, one permitted list/get, and replay `nextCursor` when present.
4. Verify a lower-role user cannot discover/read a denied entity.
5. In an isolated database, enable writes, create/read/delete a disposable draft, preview posting,
   and post only with explicit authorization.
