package su.onno.mcp;

import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.access.Subject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.types.Ref;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.CatalogQueryService;
import su.onno.ui.DocumentCommandService;
import su.onno.ui.DocumentQueryService;
import su.onno.ui.RecordScopeCompiler;
import su.onno.ui.RegisterQueryService;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MCP half of the record-policy conformance suite: the generated tools run inside the calling
 * user's record scope, exactly like the UI — no tool result contains another owner's record.
 */
class McpRecordPolicyLeakTest {

    @Catalog(name = "McpOwners")
    @AccessControl(readRoles = {"CUSTOMER"})
    public static class McpOwner extends CatalogObject {
        @Attribute
        private String email;
    }

    @Catalog(name = "McpTenants")
    @AccessControl(readRoles = {"CUSTOMER"})
    public static class McpTenant extends CatalogObject {
        @Attribute
        private Ref<McpOwner> owner;
    }

    private MetadataToolFactory tools;
    private UUID ownerA;
    private UUID ownerB;
    private UUID tenantA;
    private UUID tenantB;
    private AccessSubject.User customerA;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        CatalogDescriptor owners = scanner.scan(McpOwner.class);
        CatalogDescriptor tenants = scanner.scan(McpTenant.class);
        registry.registerCatalog(owners);
        registry.registerCatalog(tenants);
        new SchemaGenerator(registry).execute(jdbi);

        RecordPolicies policies = new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(McpTenant.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("owner", Subject.recordId()))));
        RecordScopeCompiler compiler = new RecordScopeCompiler(policies);
        UiAccessService access = new UiAccessService(registry);
        CatalogQueryService catalogQuery = new CatalogQueryService(registry, jdbi, compiler, access);
        AtomicInteger seq = new AtomicInteger();
        NumberGenerator numbers = new NumberGenerator() {
            @Override public String nextNumber(String entity, int length) {
                return "N" + seq.incrementAndGet();
            }

            @Override public String nextCode(String entity, int length) {
                return "C" + seq.incrementAndGet();
            }
        };
        CatalogCommandService catalogCommands = new CatalogCommandService(registry, jdbi, new UiProperties(),
                numbers, catalogQuery, access, event -> { }, new SecretCipher(null));

        AccessSubject system = AccessSubject.system();
        ownerA = id(catalogCommands.create(owners, body("description", "Owner Alpha", "email", "a@x"), system));
        ownerB = id(catalogCommands.create(owners, body("description", "LEAK-B-owner", "email", "b@x"), system));
        tenantA = id(catalogCommands.create(tenants, body("description", "Alpha", "owner", ownerA.toString()), system));
        tenantB = id(catalogCommands.create(tenants, body("description", "LEAK-B-tenant", "owner", ownerB.toString()), system));
        customerA = new AccessSubject.User("a@x", Set.of("CUSTOMER"), ownerA, Map.of());

        OnnoMcpProperties props = new OnnoMcpProperties();
        props.setWritesEnabled(true);
        tools = new MetadataToolFactory(registry, null, access, catalogQuery, mock(DocumentQueryService.class),
                mock(RegisterQueryService.class), catalogCommands, mock(DocumentCommandService.class), props,
                new UiProperties(), new JacksonMcpJsonMapper(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void toolsRunInsideTheCallersRecordScope() {
        String list = text(call("list_catalog", Map.of("name", "McpTenants")));
        assertThat(list).contains(tenantA.toString()).doesNotContain(tenantB.toString()).doesNotContain("LEAK-B");

        String search = text(call("list_catalog", Map.of("name", "McpTenants", "query", "LEAK")));
        assertThat(search).doesNotContain(tenantB.toString()).doesNotContain("LEAK-B");

        CallToolResult get = call("get_catalog", Map.of("name", "McpTenants", "id", tenantB.toString()));
        assertThat(get.isError()).isTrue();
        assertThat(text(get)).doesNotContain("LEAK-B");

        CallToolResult update = call("update_catalog", Map.of("name", "McpTenants", "id", tenantB.toString(),
                "values", Map.of("description", "pwned")));
        assertThat(update.isError()).isTrue();

        CallToolResult delete = call("delete_catalog", Map.of("name", "McpTenants", "id", tenantB.toString()));
        assertThat(delete.isError()).isTrue();

        String mine = text(call("get_catalog", Map.of("name", "McpTenants", "id", tenantA.toString())));
        assertThat(mine).contains("Alpha");
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        SyncToolSpecification spec = tools.build().stream()
                .filter(t -> t.tool().name().equals(tool)).findFirst().orElseThrow();
        return spec.callHandler().apply(exchange(), new CallToolRequest(tool, args));
    }

    private McpSyncServerExchange exchange() {
        McpTransportContext transport = McpTransportContext.create(Map.of(McpPrincipalContext.SUBJECT_KEY, customerA));
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(transport);
        return exchange;
    }

    private static String text(CallToolResult result) {
        return result.content().toString();
    }

    private static UUID id(Map<String, Object> row) {
        Object id = row.get("_id");
        return id instanceof UUID u ? u : UUID.fromString(String.valueOf(id));
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
