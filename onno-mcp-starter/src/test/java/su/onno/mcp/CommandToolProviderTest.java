package su.onno.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.annotations.Catalog;
import su.onno.command.CommandResult;
import su.onno.command.CommandSpec;
import su.onno.command.Commands;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.web.BatchRunner;
import su.onno.web.CatalogQueryService;
import su.onno.command.CommandRegistry;
import su.onno.web.CommandService;
import su.onno.web.DocumentQueryService;
import su.onno.web.AccessService;
import su.onno.web.WebProperties;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Entity commands become MCP tools that run through the shared, access-checked command path. */
class CommandToolProviderTest {

    @Catalog(name = "Clients")
    static class Client {
    }

    private final MetadataRegistry registry = new MetadataRegistry();
    private final CatalogQueryService catalogQuery = mock(CatalogQueryService.class);
    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper().findAndRegisterModules());
    private final BatchRunner batch = new BatchRunner(1);
    private final List<String> runs = new CopyOnWriteArrayList<>();
    private final UUID open = UUID.randomUUID();
    private final UUID closed = UUID.randomUUID();
    private final CatalogDescriptor clients = new CatalogDescriptor(
            "Clients", "Clients", "cat_clients", Client.class, 12, false, true, "C-", "Rentals",
            List.of("RENTALS"), List.of("RENTALS"),
            List.of(new AttributeDescriptor("state", "State", "state", String.class, 100,
                    false, false, "", 0, 0, AttributeDescriptor.Constraints.NONE, false)));

    @AfterEach
    void close() {
        batch.destroy();
    }

    private CommandToolProvider provider(OnnoMcpProperties properties) {
        registry.registerCatalog(clients);
        Commands<Client> bean = new Commands<>() {
            @Override public Class<Client> entity() { return Client.class; }

            @Override public void define(CommandSpec<Client> c) {
                c.command("block").title("Block client").description("Stops new rentals for the client.")
                        .roles("MANAGER")
                        .availableWhen(row -> !"closed".equals(row.text("state")))
                        .input("reason", i -> i.title("Reason").longText().required())
                        .input("level", i -> i.oneOf("soft", "hard"))
                        .inputRows("notes", g -> g.title("Notes").column("text", col -> col.required()))
                        .run(ctx -> {
                            runs.add(ctx.id() + ":" + ctx.input("reason") + ":" + ctx.inputRows("notes").size());
                            return CommandResult.success("Blocked").andRefresh();
                        });
                c.collectionCommand("recount").title("Recount").run(ctx -> CommandResult.ok());
                c.dynamic(live -> live.command("live").run(ctx -> CommandResult.ok()));
            }
        };
        when(catalogQuery.require("Clients")).thenReturn(clients);
        when(catalogQuery.inScope(any(), eq(clients), any(), eq(AccessMode.WRITE))).thenReturn(true);
        when(catalogQuery.get(any(), eq(clients), eq(open))).thenReturn(Map.of("state", "open"));
        when(catalogQuery.get(any(), eq(clients), eq(closed))).thenReturn(Map.of("state", "closed"));
        CommandService service = new CommandService(catalogQuery, mock(DocumentQueryService.class),
                new AccessService(registry), new CommandRegistry(List.of(bean)), new WebProperties(), batch);
        return new CommandToolProvider(registry, service, properties, json);
    }

    private static McpSyncServerExchange exchange(String... roles) {
        McpTransportContext transport = McpTransportContext.create(
                Map.of(McpPrincipalContext.SUBJECT_KEY, AccessSubject.user("maria", Set.of(roles))));
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(transport);
        return exchange;
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachStaticCommandIsAToolWithItsInputSchema() {
        List<SyncToolSpecification> tools = provider(new OnnoMcpProperties()).tools();
        assertThat(tools).extracting(t -> t.tool().name()).containsExactly("Clients_block", "Clients_recount");

        var block = tools.getFirst().tool();
        assertThat(block.title()).isEqualTo("Block client");
        assertThat(block.description()).isEqualTo("Stops new rentals for the client.");
        assertThat(block.inputSchema().required()).containsExactly("id", "reason");
        Map<String, Object> props = block.inputSchema().properties();
        assertThat(props).containsOnlyKeys("id", "reason", "level", "notes");
        assertThat((Map<String, Object>) props.get("level")).containsEntry("enum", List.of("soft", "hard"));
        assertThat((Map<String, Object>) props.get("notes")).containsEntry("type", "array");

        var recount = tools.get(1).tool();
        assertThat(recount.description()).isEqualTo("Recount");
        assertThat(recount.inputSchema().properties()).isEmpty();
    }

    @Test
    void runningAToolEnforcesRolesAvailabilityAndRunsWithInputs() {
        SyncToolSpecification block = provider(new OnnoMcpProperties()).tools().getFirst();
        Map<String, Object> args = Map.of("id", open.toString(), "reason", "unpaid",
                "notes", List.of(Map.of("text", "first")));

        CallToolResult denied = block.callHandler().apply(exchange("RENTALS"), new CallToolRequest("Clients_block", args));
        assertThat(denied.isError()).isTrue();
        assertThat(text(denied)).contains("Not allowed");

        CallToolResult outsider = block.callHandler().apply(exchange("NOBODY", "MANAGER"),
                new CallToolRequest("Clients_block", args));
        assertThat(outsider.isError()).isTrue();

        CallToolResult unavailable = block.callHandler().apply(exchange("RENTALS", "MANAGER"),
                new CallToolRequest("Clients_block", Map.of("id", closed.toString(), "reason", "x")));
        assertThat(unavailable.isError()).isTrue();
        assertThat(text(unavailable)).contains("not available");

        CallToolResult ok = block.callHandler().apply(exchange("RENTALS", "MANAGER"),
                new CallToolRequest("Clients_block", args));
        assertThat(ok.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(ok)).contains("\"refresh\":true").contains("\"severity\":\"success\"").contains("\"message\":\"Blocked\"");
        assertThat(runs).containsExactly(open + ":unpaid:1");
    }

    @Test
    void noToolsWhenWritesAreDisabled() {
        OnnoMcpProperties properties = new OnnoMcpProperties();
        properties.setWritesEnabled(false);
        assertThat(provider(properties).tools()).isEmpty();
    }

    @Test
    void toolNamesStayInTheMcpCharset() {
        assertThat(CommandToolProvider.toolName("Заказы", Client.class, "set status!")).isEqualTo("Client_set_status");
        assertThat(CommandToolProvider.toolName("Sales Orders", Client.class, "cancel")).isEqualTo("Sales_Orders_cancel");
        assertThat(CommandToolProvider.toolName("X".repeat(80), Client.class, "k")).hasSize(CommandToolProvider.MAX_NAME);
    }
}
