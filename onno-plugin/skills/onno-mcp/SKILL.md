---
name: onno-mcp
description: >-
  Configure, use, or debug onno-mcp-starter. Use when exposing an onno app over Model Context
  Protocol, connecting MCP clients to /mcp streamable HTTP, using describe_metadata, list/get
  catalog/document tools, register_balance/register_movements, create/update/delete tools,
  posting_preview/post_document/unpost_document, configuring onno.mcp.* properties, security,
  write/posting gates, or ensuring LLM tools obey the same RBAC as the UI.
---

# onno MCP

MCP tools are generated from metadata and run as the authenticated user. The model gets no access a
human user would not have.

## Tool Groups

- Discovery: `describe_metadata`
- Reads: `list_catalog`, `get_catalog`, `list_documents`, `get_document`
- Registers: `register_balance`, `register_movements`
- Writes: `create_catalog`, `update_catalog`, `delete_catalog`, `create_document`,
  `update_document`, `delete_document`
- Posting: `posting_preview`, `post_document`, `unpost_document`

Read [references/examples.md](references/examples.md) for dependency/config/client examples and
agent workflow.
