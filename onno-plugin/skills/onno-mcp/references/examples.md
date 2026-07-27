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
  mcp:
    enabled: true
    endpoint: /mcp
    writes-enabled: true
    posting-enabled: true
    server-name: acme-onno
```

Disable writes or posting in environments where the model should inspect only:

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
- A null or anonymous principal is denied everything.

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
- If auth fails, remember `/mcp` is stateless and authenticates per request.
- If a tool sees stale metadata, restart the app after changing model classes.
