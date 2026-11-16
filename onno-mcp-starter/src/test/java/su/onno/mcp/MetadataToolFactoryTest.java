package su.onno.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.AccumulationType;
import su.onno.model.PostingOrder;
import su.onno.web.CatalogCommandService;
import su.onno.web.CatalogQueryService;
import su.onno.web.DocumentCommandService;
import su.onno.web.DocumentQueryService;
import su.onno.web.RegisterQueryService;
import su.onno.web.AccessService;
import su.onno.web.WebProperties;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.server.McpSyncServerExchange;

import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataToolFactoryTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final CatalogQueryService catalogQuery = mock(CatalogQueryService.class);
    private final DocumentQueryService documentQuery = mock(DocumentQueryService.class);
    private final RegisterQueryService registerQuery = mock(RegisterQueryService.class);
    private final CatalogCommandService catalogCommands = mock(CatalogCommandService.class);
    private final DocumentCommandService documentCommands = mock(DocumentCommandService.class);
    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper().findAndRegisterModules());

    private final CatalogDescriptor clients = new CatalogDescriptor(
            "Clients", "Clients", "cat_clients", Object.class, 12, false, true, "C-", "Rentals",
            List.of("RENTALS"), List.of("RENTALS"),
            List.of(new AttributeDescriptor("name", "Name", "name", String.class, 100,
                    true, false, "", 0, 0, AttributeDescriptor.Constraints.NONE, false)));

    private final AccumulationRegisterDescriptor cash = new AccumulationRegisterDescriptor(
            "Cash", "Cash", "reg_cash", "reg_cash_totals", Object.class,
            AccumulationType.BALANCE, false, PostingOrder.INDEPENDENT, "Finance",
            List.of("RENTALS"), List.of("RENTALS"), clients.attributes(), List.of());

    MetadataToolFactoryTest() {
        registry.registerCatalog(clients);
        registry.registerAccumulation(cash);
    }

    private MetadataToolFactory factory(OnnoMcpProperties props) {
        return factory(props, new WebProperties());
    }

    private MetadataToolFactory factory(OnnoMcpProperties props, WebProperties webProperties) {
        AccessService access = new AccessService(registry);
        return new MetadataToolFactory(registry, null, access, catalogQuery, documentQuery, registerQuery,
                catalogCommands, documentCommands, props, webProperties, json);
    }

    private static Set<String> names(List<SyncToolSpecification> tools) {
        return tools.stream().map(t -> t.tool().name()).collect(Collectors.toSet());
    }

    private static SyncToolSpecification byName(List<SyncToolSpecification> tools, String name) {
        return tools.stream().filter(t -> t.tool().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void describeMetadataIncludesCatalogSections() {
        var profile = new CatalogDescriptor("Profiles", "Profiles", "catalog_profiles", Object.class,
                9, false, true, "", "", List.of("RENTALS"), List.of("RENTALS"),
                List.of(), List.of(), List.of(new su.onno.metadata.TabularSectionDescriptor(
                "filters", "filters", "catalog_profiles_filters", Object.class, clients.attributes())));
        registry.registerCatalog(profile);
        var describe = byName(factory(new OnnoMcpProperties()).build(), "describe_metadata");
        var result = describe.callHandler().apply(exchange(),
                new CallToolRequest("describe_metadata", Map.of("kind", "catalog")));
        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(result.content().toString()).contains("Profiles", "tabularSections", "filters");
    }

    @Test
    void exposesFullToolSetByDefault() {
        Set<String> names = names(factory(new OnnoMcpProperties()).build());
        assertThat(names).contains(
                "describe_metadata", "list_catalog", "get_catalog", "list_documents", "get_document",
                "register_balance", "register_movements",
                "create_catalog", "update_catalog", "delete_catalog",
                "create_document", "update_document", "delete_document",
                "posting_preview", "post_document", "unpost_document");
    }

    @Test
    void writeAndPostingTogglesRemoveTools() {
        OnnoMcpProperties props = new OnnoMcpProperties();
        props.setWritesEnabled(false);
        props.setPostingEnabled(false);
        Set<String> names = names(factory(props).build());
        assertThat(names).contains("list_catalog", "get_document");
        assertThat(names).doesNotContain(
                "create_catalog", "update_catalog", "delete_catalog",
                "create_document", "update_document", "delete_document",
                "posting_preview", "post_document", "unpost_document");
    }

    @Test
    void uiReadOnlyGateRemovesMutationToolsButKeepsPostingPreview() {
        WebProperties ui = new WebProperties();
        ui.setReadOnly(true);

        Set<String> names = names(factory(new OnnoMcpProperties(), ui).build());

        assertThat(names).contains("posting_preview", "list_catalog");
        assertThat(names).doesNotContain(
                "create_catalog", "update_catalog", "delete_catalog",
                "create_document", "update_document", "delete_document",
                "post_document", "unpost_document");
    }

    @Test
    void describeMetadataReportsGlobalWriteGate() {
        OnnoMcpProperties properties = new OnnoMcpProperties();
        properties.setWritesEnabled(false);
        SyncToolSpecification describe = byName(factory(properties).build(), "describe_metadata");

        CallToolResult result = describe.callHandler().apply(exchange(),
                new CallToolRequest("describe_metadata", Map.of("kind", "catalog")));

        assertThat(result.content().toString())
                .contains("Clients", "writable", "false");
    }

    @Test
    void readToolDeniesAnonymousCaller() {
        when(catalogQuery.require("Clients")).thenReturn(clients);
        SyncToolSpecification listCatalog = byName(factory(new OnnoMcpProperties()).build(), "list_catalog");

        // A null exchange yields a null principal -> deny by default in AccessService.
        CallToolResult result = listCatalog.callHandler()
                .apply(null, new CallToolRequest("list_catalog", Map.of("name", "Clients")));

        assertThat(result.isError()).isTrue();
    }

    @Test
    void describeMetadataHidesEntitiesFromAnonymousCaller() {
        SyncToolSpecification describe = byName(factory(new OnnoMcpProperties()).build(), "describe_metadata");

        CallToolResult result = describe.callHandler()
                .apply(null, new CallToolRequest("describe_metadata", Map.of()));

        // Not an error, but the Clients catalog must not be disclosed to an anonymous caller.
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = result.content().toString();
        assertThat(text).doesNotContain("Clients");
    }

    @Test
    void registerMovementsReadsBeyondOneThousandRowsWithCursor() {
        SyncToolSpecification movements = movementTool();
        CallToolResult first = callMovements(movements, Map.of("name", "Cash"));
        assertThat(first.isError()).isNotEqualTo(true);
        assertThat(movementRows(first)).hasSize(100);
        Map<?, ?> page = (Map<?, ?>) ((Map<?, ?>) first.structuredContent()).get("result");
        assertThat(page.get("hasMore")).isEqualTo(true);
        var continued = callMovements(movements, Map.of("name", "Cash", "cursor", page.get("nextCursor")));
        assertThat(movementRows(continued).getFirst()).containsEntry("sequence", 902);
        assertThat(movementRows(first).getFirst()).containsEntry("sequence", 1002);

        int total = movementRows(first).size();
        var current = first;
        while (Boolean.TRUE.equals(((Map<?, ?>) ((Map<?, ?>) current.structuredContent()).get("result")).get("hasMore"))) {
            var payload = (Map<?, ?>) ((Map<?, ?>) current.structuredContent()).get("result");
            current = callMovements(movements, Map.of("name", "Cash", "cursor", payload.get("nextCursor")));
            total += movementRows(current).size();
        }
        assertThat(total).isEqualTo(1002);
        assertThat(((Map<?, ?>) ((Map<?, ?>) current.structuredContent()).get("result")).get("nextCursor")).isNull();

    }

    @Test
    void registerMovementsCombinesWherePeriodPaginationAndSort() {
        var movements = movementTool();
        var result = callMovements(movements, Map.of("name", "Cash", "where", Map.of("name", "Alpha"),
                "from", "2026-01-01T00:00:10", "to", "2026-01-01T00:00:20",
                "limit", 2, "sort", "period", "descending", false));
        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(movementRows(result)).hasSize(2);
        assertThat(movementRows(result).getFirst()).containsEntry("sequence", 10).containsEntry("name", "Alpha");
        assertThat(movementRows(result).getLast()).containsEntry("sequence", 11);

        var beta = callMovements(movements, Map.of("name", "Cash", "where", Map.of("name", "Beta")));
        assertThat(movementRows(beta)).hasSize(1);
        assertThat(movementRows(beta).getFirst()).containsEntry("sequence", 1002);
    }

    @Test
    void registerMovementsRejectsInvalidPagingAndUnknownDimensions() {
        var movements = movementTool();
        for (var arguments : List.of(Map.of("limit", 0), Map.of("limit", 501),
                Map.of("where", Map.of("unknown", "value")))) {
            Map<String, Object> args = new java.util.LinkedHashMap<>(arguments);
            args.put("name", "Cash");
            assertThat(callMovements(movements, args).isError()).isTrue();
        }
        var injected = callMovements(movements,
                Map.of("name", "Cash", "where", Map.of("name", "Alpha' OR 1=1 --")));
        assertThat(movementRows(injected)).isEmpty();
    }

    private SyncToolSpecification movementTool() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbi = Jdbi.create(dataSource);
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE reg_cash (_id UUID DEFAULT RANDOM_UUID() PRIMARY KEY, _period TIMESTAMP, _active BOOLEAN, name VARCHAR, sequence INT)");
            h.execute("INSERT INTO reg_cash (_period, _active, name, sequence) SELECT DATEADD('SECOND', X, TIMESTAMP '2026-01-01 00:00:00'), "
                    + "true, CASE WHEN X = 1002 THEN 'Beta' ELSE 'Alpha' END, X FROM SYSTEM_RANGE(1, 1002)");
            h.execute("INSERT INTO reg_cash (_period, _active, name, sequence) VALUES (TIMESTAMP '2027-01-01 00:00:00', false, 'Alpha', 1003)");
        });
        var factory = new MetadataToolFactory(registry, new AccessService(registry), catalogQuery,
                documentQuery, new RegisterQueryService(registry, jdbi), catalogCommands, documentCommands,
                new OnnoMcpProperties(), json);
        return byName(factory.build(), "register_movements");
    }

    private static CallToolResult callMovements(SyncToolSpecification tool, Map<String, Object> args) {
        return tool.callHandler().apply(exchange(), new CallToolRequest("register_movements", args));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> movementRows(CallToolResult result) {
        assertThat(result.isError()).as(result.content().toString()).isNotEqualTo(true);
        return (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("result")).get("rows");
    }

    @Test
    void registerBalanceReturnsTheSamePageEnvelope() {
        when(registerQuery.require("Cash")).thenReturn(cash);
        when(registerQuery.balancePage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(cash),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(100), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new su.onno.query.KeysetWindow.Page<>(List.of(Map.of("amount", 10)), "next", true));
        SyncToolSpecification balance = byName(factory(new OnnoMcpProperties()).build(), "register_balance");
        CallToolResult result = balance.callHandler().apply(exchange(),
                new CallToolRequest("register_balance", Map.of("name", "Cash")));
        assertThat(result.isError()).as(result.content().toString()).isNotEqualTo(true);
        assertThat(result.content().toString()).contains("10", "next");
        assertThat(balance.tool().inputSchema().properties()).doesNotContainKey("offset");
    }

    @Test
    void registerBalanceForwardsInclusiveCutoffWhereAndCursor() {
        when(registerQuery.require("Cash")).thenReturn(cash);
        when(registerQuery.balancePage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(cash),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("{\"name\":\"Main\"}"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.eq(100),
                org.mockito.ArgumentMatchers.eq("2024-03-31T23:59:59")))
                .thenReturn(new su.onno.query.KeysetWindow.Page<>(List.of(Map.of("amount", 42)), null, false));
        var balance = byName(factory(new OnnoMcpProperties()).build(), "register_balance");
        var result = balance.callHandler().apply(exchange(), new CallToolRequest("register_balance",
                Map.of("name", "Cash", "asOf", "2024-03-31T23:59:59", "where", Map.of("name", "Main"), "cursor", "c1")));
        assertThat(result.isError()).as(result.content().toString()).isNotEqualTo(true);
        assertThat(result.content().toString()).contains("42");
        assertThat(balance.tool().inputSchema().properties()).containsKey("asOf").containsKey("where");
    }

    private static McpSyncServerExchange exchange() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "maria", "n/a", List.of(new SimpleGrantedAuthority("ROLE_RENTALS")));
        McpTransportContext transport = McpTransportContext.create(
                Map.of(McpPrincipalContext.PRINCIPAL_KEY, authentication));
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(transport);
        return exchange;
    }

    @Test
    void temporalFieldsAdvertiseTheirWireFormat() {
        assertThat(MetadataToolFactory.temporalFormat(java.time.LocalTime.class)).startsWith("HH:mm");
        assertThat(MetadataToolFactory.temporalFormat(java.time.Instant.class)).contains("Z").contains("UTC");
        assertThat(MetadataToolFactory.temporalFormat(java.time.LocalDate.class)).isEqualTo("yyyy-MM-dd");
        assertThat(MetadataToolFactory.temporalFormat(String.class)).isNull();
    }
}
