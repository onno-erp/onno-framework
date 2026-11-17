package su.onno.mcp;

import su.onno.access.AccessSubject;
import su.onno.command.Command;
import su.onno.command.CommandInput;
import su.onno.command.CommandInputGroup;
import su.onno.command.CommandRejectedException;
import su.onno.command.CommandFeedback;
import su.onno.command.CommandResult;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.service.CommandService;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Exposes every static {@link su.onno.command.Commands command} of every catalog and document as an
 * MCP tool, so an AI agent can do what a user does with the same rules.
 *
 * <ul>
 *   <li><b>Name</b>: {@code <entity>_<key>} restricted to {@code [A-Za-z0-9_.-]} (other characters
 *       become {@code _}; an entity whose logical name has no ASCII letters uses its Java class
 *       name), at most 64 characters, de-duplicated with a numeric suffix.</li>
 *   <li><b>Description</b>: the command's {@code description}, else its title.</li>
 *   <li><b>Input schema</b>: one property per input (strings; {@code number}, a {@code date}
 *       format, an {@code enum} of options or a referenced-record UUID by input type), one array
 *       of objects per row group, and a required {@code id} (the record UUID) for record
 *       commands.</li>
 * </ul>
 *
 * <p>A call runs through {@link CommandService} with the caller's {@link AccessSubject}: entity
 * write access, the command's roles, read-only mode, record scope and the per-record
 * availability/enabled state are enforced exactly as on the REST endpoint. Late-bound
 * ({@code dynamic(...)}) commands change at runtime and are not published as tools. Tools are
 * published only when {@code onno.mcp.writes-enabled} is on.</p>
 */
public class CommandToolProvider implements McpToolProvider {

    static final int MAX_NAME = 64;

    private final MetadataRegistry registry;
    private final CommandService commands;
    private final OnnoMcpProperties properties;
    private final McpJsonMapper json;

    public CommandToolProvider(MetadataRegistry registry, CommandService commands,
                               OnnoMcpProperties properties, McpJsonMapper json) {
        this.registry = registry;
        this.commands = commands;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public List<SyncToolSpecification> tools() {
        if (!properties.isWritesEnabled()) {
            return List.of();
        }
        List<SyncToolSpecification> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (CatalogDescriptor d : registry.allCatalogs()) {
            addTools(out, used, "catalogs", d.logicalName(), d.javaClass());
        }
        for (DocumentDescriptor d : registry.allDocuments()) {
            addTools(out, used, "documents", d.logicalName(), d.javaClass());
        }
        return out;
    }

    private void addTools(List<SyncToolSpecification> out, Set<String> used, String kind, String name,
                          Class<?> type) {
        for (Command<?> command : commands.registry().staticCommands(type)) {
            String toolName = unique(toolName(name, type, command.key()), used);
            out.add(tool(toolName, command, (exchange, args) -> {
                AccessSubject subject = McpPrincipalContext.subject(exchange);
                UUID id = command.isRecordCommand() ? requireId(args) : null;
                CommandResult result = commands.run(kind, name, command.key(), id,
                        Map.of("inputs", inputs(command, args)), subject);
                return ok(result);
            }));
        }
    }

    /** {@code <entity>_<key>} restricted to the MCP tool-name charset. */
    static String toolName(String logicalName, Class<?> type, String key) {
        String entity = sanitize(logicalName);
        if (!entity.matches(".*[A-Za-z].*")) {
            entity = sanitize(type.getSimpleName());
        }
        String name = entity + "_" + sanitize(key);
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    private static String sanitize(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_.-]+", "_");
        return s.replaceAll("^_+|_+$", "");
    }

    private static String unique(String name, Set<String> used) {
        String candidate = name;
        for (int i = 2; !used.add(candidate); i++) {
            String suffix = "_" + i;
            candidate = (name.length() + suffix.length() > MAX_NAME
                    ? name.substring(0, MAX_NAME - suffix.length()) : name) + suffix;
        }
        return candidate;
    }

    /** The JSON schema of a command's arguments. */
    static Map<String, Object> inputSchema(Command<?> command) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (command.isRecordCommand()) {
            props.put("id", Map.of("type", "string", "format", "uuid", "description", "Record id (UUID)."));
            required.add("id");
        }
        for (CommandInput in : command.inputs()) {
            props.put(in.key(), inputProperty(in));
            if (in.required()) {
                required.add(in.key());
            }
        }
        for (CommandInputGroup g : command.inputGroups()) {
            Map<String, Object> rowProps = new LinkedHashMap<>();
            List<String> rowRequired = new ArrayList<>();
            for (CommandInput col : g.columns()) {
                rowProps.put(col.key(), inputProperty(col));
                if (col.required()) {
                    rowRequired.add(col.key());
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "object");
            row.put("properties", rowProps);
            if (!rowRequired.isEmpty()) {
                row.put("required", rowRequired);
            }
            Map<String, Object> array = new LinkedHashMap<>();
            array.put("type", "array");
            array.put("description", g.title());
            array.put("items", row);
            if (g.required()) {
                array.put("minItems", 1);
                required.add(g.key());
            }
            props.put(g.key(), array);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> inputProperty(CommandInput in) {
        Map<String, Object> p = new LinkedHashMap<>();
        String description = in.title();
        switch (in.type()) {
            case NUMBER -> p.put("type", "number");
            case DATE -> {
                p.put("type", "string");
                p.put("format", "date");
            }
            case SELECT -> {
                p.put("type", "string");
                if (!in.options().isEmpty()) {
                    p.put("enum", in.options());
                }
            }
            case REFERENCE -> {
                p.put("type", "string");
                p.put("format", "uuid");
                description = description + " — id of a " + in.reference() + " " + in.refKind() + " record";
            }
            default -> p.put("type", "string");
        }
        p.put("description", description);
        if (!in.defaultValue().isBlank()) {
            p.put("default", in.defaultValue());
        }
        return p;
    }

    /** The command's declared inputs picked out of the tool arguments (unknown arguments ignored). */
    private static Map<String, Object> inputs(Command<?> command, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (CommandInput in : command.inputs()) {
            if (args.containsKey(in.key())) {
                out.put(in.key(), args.get(in.key()));
            }
        }
        for (CommandInputGroup g : command.inputGroups()) {
            if (args.get(g.key()) instanceof List<?> rows) {
                out.put(g.key(), rows);
            }
        }
        return out;
    }

    private SyncToolSpecification tool(String name, Command<?> command,
                                       BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> body) {
        String description = command.description().isBlank() ? command.title() : command.description();
        Tool tool;
        try {
            tool = Tool.builder()
                    .name(name)
                    .title(command.title())
                    .description(description)
                    .inputSchema(json, json.writeValueAsString(inputSchema(command)))
                    .annotations(new ToolAnnotations(null, false, false, false, false, false))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build the input schema of command tool " + name, e);
        }
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            try {
                return body.apply(exchange, args);
            } catch (CommandRejectedException e) {
                return error(e.getMessage(), e.feedback());
            } catch (su.onno.service.ServiceException refused) {
                return error(refused.getMessage() == null ? refused.status().name() : refused.getMessage(), null);
            } catch (ResponseStatusException e) {
                return error(e.getReason() == null ? e.getMessage() : e.getReason(), null);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage(), null);
            } catch (Exception e) {
                return error(e.getClass().getSimpleName() + ": " + e.getMessage(), null);
            }
        };
        return SyncToolSpecification.builder().tool(tool).callHandler(handler).build();
    }

    private CallToolResult ok(CommandResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", true);
        value.put("refresh", result.refresh());
        if (result.feedback() != null) {
            // An agent reads meaning, not presentation: the severity and the feedback as one line.
            value.put("severity", result.feedback().severity().wireValue());
            value.put("message", result.feedback().asText());
        }
        try {
            return CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(value))
                    .structuredContent(Map.of("result", value))
                    .build();
        } catch (Exception e) {
            return error("Failed to serialize result: " + e.getMessage(), null);
        }
    }

    private CallToolResult error(String message, CommandFeedback feedback) {
        String text = message == null ? "error" : message;
        if (feedback != null) {
            StringBuilder sb = new StringBuilder(feedback.severity().wireValue()).append(": ")
                    .append(feedback.asText().isBlank() ? text : feedback.asText());
            feedback.formErrors().forEach(e -> sb.append("\n- ").append(e));
            feedback.fieldErrors().forEach((field, errors) ->
                    errors.forEach(e -> sb.append("\n- ").append(field).append(": ").append(e)));
            text = sb.toString();
        }
        return CallToolResult.builder().isError(true).addTextContent(text).build();
    }

    private static UUID requireId(Map<String, Object> args) {
        Object v = args.get("id");
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: id");
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Argument 'id' must be a UUID: " + v);
        }
    }
}
