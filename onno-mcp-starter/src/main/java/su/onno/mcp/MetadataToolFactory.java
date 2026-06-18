package su.onno.mcp;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentCommandService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.RegisterQueryService;
import su.onno.ui.UiAccessService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Generates the MCP tool set generically from the {@link MetadataRegistry}.
 *
 * <p>Every tool resolves a descriptor by logical name, enforces access through
 * {@link UiAccessService} against the principal captured by {@link McpPrincipalContext},
 * and then delegates to the shared query/command services — the same code path the web
 * UI uses. There is no per-entity code: adding an entity to the application surfaces it
 * here automatically, and a {@code describe_metadata} discovery tool lets the model learn
 * entity, field, and enum names at runtime.
 */
public class MetadataToolFactory {

    private final MetadataRegistry registry;
    private final UiAccessService access;
    private final CatalogQueryService catalogQuery;
    private final DocumentQueryService documentQuery;
    private final RegisterQueryService registerQuery;
    private final CatalogCommandService catalogCommands;
    private final DocumentCommandService documentCommands;
    private final OnnoMcpProperties properties;
    private final McpJsonMapper json;

    public MetadataToolFactory(MetadataRegistry registry, UiAccessService access,
                               CatalogQueryService catalogQuery, DocumentQueryService documentQuery,
                               RegisterQueryService registerQuery, CatalogCommandService catalogCommands,
                               DocumentCommandService documentCommands, OnnoMcpProperties properties,
                               McpJsonMapper json) {
        this.registry = registry;
        this.access = access;
        this.catalogQuery = catalogQuery;
        this.documentQuery = documentQuery;
        this.registerQuery = registerQuery;
        this.catalogCommands = catalogCommands;
        this.documentCommands = documentCommands;
        this.properties = properties;
        this.json = json;
    }

    public List<SyncToolSpecification> build() {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(readTool("describe_metadata",
                "Describe the business model",
                "Lists the catalogs, documents and accumulation registers the current user may read, "
                        + "with their fields and enum values. Call this first to discover the exact entity "
                        + "names and field names to pass to the other tools.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"kind\":{\"type\":\"string\",\"enum\":[\"catalog\",\"document\",\"register\",\"all\"],"
                        + "\"description\":\"Restrict the description to one entity kind. Defaults to all.\"}}}",
                (exchange, args) -> describeMetadata(principal(exchange), optString(args, "kind"))));

        tools.add(readTool("list_catalog",
                "List catalog records",
                "Lists records of a catalog by its logical name. Provide 'query' and/or 'limit' for a "
                        + "capped, case-insensitive search over code/description; otherwise all live records are returned.",
                "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"Catalog logical name (see describe_metadata).\"},"
                        + "\"query\":{\"type\":\"string\",\"description\":\"Optional search text.\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"Optional max rows (1-200).\"}}}",
                (exchange, args) -> {
                    CatalogDescriptor desc = catalogQuery.require(requireString(args, "name"));
                    access.requireRead(principal(exchange), desc);
                    String q = optString(args, "query");
                    Integer limit = optInt(args, "limit");
                    if (q != null || limit != null) {
                        int cap = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
                        return ok(catalogQuery.search(desc, q, cap));
                    }
                    return ok(catalogQuery.list(desc));
                }));

        tools.add(readTool("get_catalog",
                "Get one catalog record",
                "Returns a single catalog record by id, with reference fields resolved.",
                idSchema("Catalog logical name (see describe_metadata)."),
                (exchange, args) -> {
                    CatalogDescriptor desc = catalogQuery.require(requireString(args, "name"));
                    access.requireRead(principal(exchange), desc);
                    return ok(catalogQuery.get(desc, requireUuid(args, "id")));
                }));

        tools.add(readTool("list_documents",
                "List documents",
                "Lists documents of a given type by logical name, optionally filtered by an inclusive "
                        + "date range (ISO-8601 strings), newest first.",
                "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"Document logical name (see describe_metadata).\"},"
                        + "\"from\":{\"type\":\"string\",\"description\":\"Optional inclusive start date/time (ISO-8601).\"},"
                        + "\"to\":{\"type\":\"string\",\"description\":\"Optional inclusive end date/time (ISO-8601).\"}}}",
                (exchange, args) -> {
                    DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                    access.requireRead(principal(exchange), desc);
                    return ok(documentQuery.list(desc, optString(args, "from"), optString(args, "to")));
                }));

        tools.add(readTool("get_document",
                "Get one document",
                "Returns a single document by id, including its tabular sections and resolved references.",
                idSchema("Document logical name (see describe_metadata)."),
                (exchange, args) -> {
                    DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                    access.requireRead(principal(exchange), desc);
                    return ok(documentQuery.get(desc, requireUuid(args, "id")));
                }));

        tools.add(readTool("register_balance",
                "Get accumulation register balances",
                "Returns current balances for a BALANCE accumulation register, optionally filtered by "
                        + "dimension field values.",
                "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"Register logical name (see describe_metadata).\"},"
                        + "\"filters\":{\"type\":\"object\",\"description\":\"Optional map of dimension fieldName -> value.\"}}}",
                (exchange, args) -> {
                    AccumulationRegisterDescriptor desc = registerQuery.require(requireString(args, "name"));
                    access.requireRead(principal(exchange), desc);
                    return ok(registerQuery.balance(desc, stringMap(args.get("filters"))));
                }));

        tools.add(readTool("register_movements",
                "Get accumulation register movements",
                "Returns active movements (postings) for an accumulation register, optionally filtered by "
                        + "an inclusive period range (ISO-8601), newest first.",
                "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"Register logical name (see describe_metadata).\"},"
                        + "\"from\":{\"type\":\"string\",\"description\":\"Optional inclusive start period (ISO-8601).\"},"
                        + "\"to\":{\"type\":\"string\",\"description\":\"Optional inclusive end period (ISO-8601).\"}}}",
                (exchange, args) -> {
                    AccumulationRegisterDescriptor desc = registerQuery.require(requireString(args, "name"));
                    access.requireRead(principal(exchange), desc);
                    return ok(registerQuery.movements(desc, optString(args, "from"), optString(args, "to")));
                }));

        if (properties.isWritesEnabled()) {
            tools.add(writeTool("create_catalog",
                    "Create a catalog record",
                    "Creates a new catalog record. 'values' may include code, description, folder (boolean), "
                            + "parent (id) and any attribute field. Returns the created record.",
                    valuesSchema("Catalog logical name (see describe_metadata).", false),
                    (exchange, args) -> {
                        CatalogDescriptor desc = catalogQuery.require(requireString(args, "name"));
                        return ok(catalogCommands.create(desc, values(args), principal(exchange)));
                    }));

            tools.add(writeTool("update_catalog",
                    "Update a catalog record",
                    "Updates fields of an existing catalog record by id. Only keys present in 'values' are "
                            + "changed; include 'version' for optimistic locking. Returns the updated record.",
                    valuesSchema("Catalog logical name (see describe_metadata).", true),
                    (exchange, args) -> {
                        CatalogDescriptor desc = catalogQuery.require(requireString(args, "name"));
                        return ok(catalogCommands.update(desc, requireUuid(args, "id"), values(args), principal(exchange)));
                    }));

            tools.add(destructiveTool("delete_catalog",
                    "Delete a catalog record",
                    "Soft-deletes an existing catalog record by id. Returns a small acknowledgement.",
                    idSchema("Catalog logical name (see describe_metadata)."),
                    (exchange, args) -> {
                        CatalogDescriptor desc = catalogQuery.require(requireString(args, "name"));
                        UUID id = requireUuid(args, "id");
                        catalogCommands.delete(desc, id, principal(exchange));
                        return ok(Map.of("deleted", true, "entityType", "catalog",
                                "name", desc.logicalName(), "id", id.toString()));
                    }));

            tools.add(writeTool("create_document",
                    "Create a document",
                    "Creates a new (unposted) document. 'values' may include number, date, attribute fields, "
                            + "and tabular sections (each a list of row objects keyed by section name). "
                            + "Returns the created document. Use post_document to post it to registers.",
                    valuesSchema("Document logical name (see describe_metadata).", false),
                    (exchange, args) -> {
                        DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                        return ok(documentCommands.create(desc, values(args), principal(exchange)));
                    }));

            tools.add(writeTool("update_document",
                    "Update a document",
                    "Updates an existing document by id. Only keys present in 'values' are changed; include "
                            + "'version' for optimistic locking. Returns the updated document.",
                    valuesSchema("Document logical name (see describe_metadata).", true),
                    (exchange, args) -> {
                        DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                        return ok(documentCommands.update(desc, requireUuid(args, "id"), values(args), principal(exchange)));
                    }));

            tools.add(destructiveTool("delete_document",
                    "Delete a document",
                    "Soft-deletes an existing document by id. If it is posted, it is unposted first. "
                            + "Returns a small acknowledgement.",
                    idSchema("Document logical name (see describe_metadata)."),
                    (exchange, args) -> {
                        DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                        UUID id = requireUuid(args, "id");
                        documentCommands.delete(desc, id, principal(exchange));
                        return ok(Map.of("deleted", true, "entityType", "document",
                                "name", desc.logicalName(), "id", id.toString()));
                    }));
        }

        if (properties.isPostingEnabled()) {
            tools.add(readTool("posting_preview",
                    "Preview document posting",
                    "Computes the register movements a document WOULD make if posted, without writing "
                            + "anything. Safe, read-only.",
                    idSchema("Document logical name (see describe_metadata)."),
                    (exchange, args) -> {
                        DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                        return ok(documentCommands.postingPreview(desc, requireUuid(args, "id"), principal(exchange)));
                    }));

            tools.add(writeTool("post_document",
                    "Post a document",
                    "Posts a document, writing its movements to the accumulation/information registers. "
                            + "This has ledger side-effects. Returns the posted document.",
                    idSchema("Document logical name (see describe_metadata)."),
                    (exchange, args) -> {
                        DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                        return ok(documentCommands.post(desc, requireUuid(args, "id"), principal(exchange)));
                    }));

            tools.add(writeTool("unpost_document",
                    "Unpost a document",
                    "Reverses a document's posting, removing its register movements. Returns the document.",
                    idSchema("Document logical name (see describe_metadata)."),
                    (exchange, args) -> {
                        DocumentDescriptor desc = documentQuery.require(requireString(args, "name"));
                        return ok(documentCommands.unpost(desc, requireUuid(args, "id"), principal(exchange)));
                    }));
        }

        return tools;
    }

    // ---- describe_metadata ------------------------------------------------------------

    private CallToolResult describeMetadata(Principal principal, String kind) {
        String k = kind == null ? "all" : kind.toLowerCase();
        Map<String, Object> out = new LinkedHashMap<>();

        if (k.equals("all") || k.equals("catalog")) {
            List<Map<String, Object>> catalogs = new ArrayList<>();
            for (CatalogDescriptor d : registry.allCatalogs()) {
                if (!access.canRead(principal, d)) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", d.logicalName());
                e.put("hierarchical", d.hierarchical());
                e.put("writable", access.canWrite(principal, d));
                e.put("fields", fields(d.attributes()));
                catalogs.add(e);
            }
            out.put("catalogs", catalogs);
        }

        if (k.equals("all") || k.equals("document")) {
            List<Map<String, Object>> documents = new ArrayList<>();
            for (DocumentDescriptor d : registry.allDocuments()) {
                if (!access.canRead(principal, d)) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", d.logicalName());
                e.put("writable", access.canWrite(principal, d));
                e.put("fields", fields(d.attributes()));
                List<Map<String, Object>> sections = new ArrayList<>();
                for (TabularSectionDescriptor ts : d.tabularSections()) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("name", ts.name());
                    s.put("fields", fields(ts.attributes()));
                    sections.add(s);
                }
                if (!sections.isEmpty()) e.put("tabularSections", sections);
                documents.add(e);
            }
            out.put("documents", documents);
        }

        if (k.equals("all") || k.equals("register")) {
            List<Map<String, Object>> registers = new ArrayList<>();
            for (AccumulationRegisterDescriptor d : registry.allRegisters()) {
                if (!access.canRead(principal, d)) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", d.logicalName());
                e.put("type", d.accumulationType() == null ? null : d.accumulationType().name());
                e.put("dimensions", fields(d.dimensions()));
                e.put("resources", fields(d.resources()));
                registers.add(e);
            }
            out.put("registers", registers);
        }

        if (k.equals("all")) {
            List<Map<String, Object>> enums = new ArrayList<>();
            for (EnumerationDescriptor d : registry.allEnumerations()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", d.logicalName());
                List<String> values = new ArrayList<>();
                d.values().forEach(v -> values.add(v.name() + "=" + v.id()));
                e.put("values", values);
                enums.add(e);
            }
            out.put("enumerations", enums);
        }

        return ok(out);
    }

    private List<Map<String, Object>> fields(List<AttributeDescriptor> attributes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttributeDescriptor a : attributes) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("field", a.fieldName());
            f.put("label", a.displayName());
            f.put("type", a.isRef() ? "ref" : a.javaType().getSimpleName());
            if (a.isRef() && a.refTarget() != null && !a.refTarget().isBlank()) {
                f.put("refTarget", a.refTarget());
            }
            if (a.required()) f.put("required", true);
            out.add(f);
        }
        return out;
    }

    // ---- tool construction ------------------------------------------------------------

    private SyncToolSpecification readTool(String name, String title, String description, String schema,
                                           BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> body) {
        return tool(name, title, description, schema,
                new ToolAnnotations(null, true, false, false, false, false), body);
    }

    private SyncToolSpecification writeTool(String name, String title, String description, String schema,
                                            BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> body) {
        return tool(name, title, description, schema,
                new ToolAnnotations(null, false, false, false, false, false), body);
    }

    private SyncToolSpecification destructiveTool(String name, String title, String description, String schema,
                                                  BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> body) {
        return tool(name, title, description, schema,
                new ToolAnnotations(null, false, true, false, false, false), body);
    }

    private SyncToolSpecification tool(String name, String title, String description, String schema,
                                       ToolAnnotations annotations,
                                       BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> body) {
        Tool tool = Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(json, schema)
                .annotations(annotations)
                .build();
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            try {
                return body.apply(exchange, args);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                return error(e.getReason() == null ? e.getMessage() : e.getReason());
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            } catch (Exception e) {
                return error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        };
        return SyncToolSpecification.builder().tool(tool).callHandler(handler).build();
    }

    // ---- result + argument helpers ----------------------------------------------------

    private CallToolResult ok(Object value) {
        try {
            return CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(value))
                    .structuredContent(Map.of("result", value))
                    .build();
        } catch (Exception e) {
            return error("Failed to serialize result: " + e.getMessage());
        }
    }

    private CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message == null ? "error" : message).build();
    }

    private static Principal principal(McpSyncServerExchange exchange) {
        return McpPrincipalContext.principal(exchange);
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.toString();
    }

    private static String optString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static Integer optInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static UUID requireUuid(Map<String, Object> args, String key) {
        String v = requireString(args, key);
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a UUID: " + v);
        }
    }

    private static Map<String, Object> values(Map<String, Object> args) {
        Object v = args.get("values");
        Map<String, Object> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            // Copy into a checked map (keys stringified) rather than an unchecked cast that
            // assumes the client sent String keys.
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue().toString());
                }
            }
        }
        return out;
    }

    private static String idSchema(String nameDescription) {
        return "{\"type\":\"object\",\"required\":[\"name\",\"id\"],\"properties\":{"
                + "\"name\":{\"type\":\"string\",\"description\":\"" + nameDescription + "\"},"
                + "\"id\":{\"type\":\"string\",\"description\":\"Record id (UUID).\"}}}";
    }

    private static String valuesSchema(String nameDescription, boolean withId) {
        StringBuilder required = new StringBuilder("[\"name\",\"values\"");
        StringBuilder props = new StringBuilder();
        props.append("\"name\":{\"type\":\"string\",\"description\":\"").append(nameDescription).append("\"},");
        if (withId) {
            required.append(",\"id\"");
            props.append("\"id\":{\"type\":\"string\",\"description\":\"Record id (UUID).\"},");
        }
        props.append("\"values\":{\"type\":\"object\",\"description\":\"Field values keyed by field name "
                + "(see describe_metadata). Reference/enum fields take an id string.\"}");
        required.append("]");
        return "{\"type\":\"object\",\"required\":" + required + ",\"properties\":{" + props + "}}";
    }
}
