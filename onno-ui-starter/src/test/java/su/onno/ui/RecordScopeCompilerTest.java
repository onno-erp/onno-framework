package su.onno.ui;

import su.onno.access.AccessMode;
import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.access.RecordScopeEvaluator;
import su.onno.access.ScopedEntity;
import su.onno.access.Subject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Enumeration;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL form of a {@link RecordScope} agrees with the in-memory evaluator on a real database:
 * subject placeholders, collections, enums, {@code via}, and three-valued logic under {@code NOT}.
 */
class RecordScopeCompilerTest {

    @Enumeration(name = "RscStatuses")
    public enum Status { ACTIVE, ARCHIVED }

    @Catalog(name = "RscOwners")
    @AccessControl(readRoles = "CUSTOMER")
    public static class Owner extends CatalogObject {
    }

    @Catalog(name = "RscTenants")
    @AccessControl(readRoles = "CUSTOMER")
    public static class Tenant extends CatalogObject {
        @Attribute
        private Ref<Owner> owner;
        @Attribute
        private Status status;
        @Attribute
        private String region;
    }

    @Catalog(name = "RscProjects")
    @AccessControl(readRoles = "CUSTOMER")
    public static class Project extends CatalogObject {
        @Attribute
        private Ref<Tenant> tenant;
    }

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private CatalogDescriptor tenants;
    private CatalogDescriptor projects;
    private final UUID ownerA = UUID.randomUUID();
    private final UUID ownerB = UUID.randomUUID();
    private final UUID tenantEuActive = UUID.randomUUID();
    private final UUID tenantUsArchived = UUID.randomUUID();
    private final UUID tenantOwnerless = UUID.randomUUID();
    private final UUID tenantOfB = UUID.randomUUID();
    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry = new MetadataRegistry();
        registry.registerEnumeration(scanner.scanEnumeration(Status.class));
        registry.registerCatalog(scanner.scan(Owner.class));
        tenants = scanner.scan(Tenant.class);
        projects = scanner.scan(Project.class);
        registry.registerCatalog(tenants);
        registry.registerCatalog(projects);
        new SchemaGenerator(registry).execute(jdbi);

        UUID active = registry.getEnumerationDescriptor(Status.class).values().get(0).id();
        UUID archived = registry.getEnumerationDescriptor(Status.class).values().get(1).id();
        insertTenant(tenantEuActive, ownerA, active, "eu");
        insertTenant(tenantUsArchived, ownerA, archived, "us");
        insertTenant(tenantOwnerless, null, active, null);
        insertTenant(tenantOfB, ownerB, active, "eu");
        insertProject(projectA, tenantEuActive);
        insertProject(projectB, tenantOfB);
    }

    private AccessSubject.User customerA() {
        return new AccessSubject.User("a", Set.of("CUSTOMER"), ownerA, Map.of("regions", List.of("eu", "apac")));
    }

    @Test
    void compiledScopesSelectExactlyWhatTheEvaluatorAccepts() {
        RecordPolicies policies = RecordPolicies.none(registry);
        RecordScopeCompiler compiler = new RecordScopeCompiler(policies);
        RecordScopeEvaluator evaluator = new RecordScopeEvaluator(policies);
        ScopedEntity tenant = policies.entity(Tenant.class);
        AccessSubject a = customerA();
        AccessSubject noRecord = AccessSubject.user("x", Set.of("CUSTOMER"));

        List<RecordScope> scopes = List.of(
                RecordScope.eq("owner", Subject.recordId()),
                RecordScope.not(RecordScope.eq("owner", Subject.recordId())),
                RecordScope.in("region", Subject.attribute("regions")),
                RecordScope.in("region", List.of()),
                RecordScope.eq("status", Status.ACTIVE),
                RecordScope.isNull("owner"),
                RecordScope.or(RecordScope.isNull("owner"), RecordScope.eq("region", "us")),
                RecordScope.and(RecordScope.eq("owner", Subject.recordId()), RecordScope.not(RecordScope.eq("status", Status.ARCHIVED))),
                RecordScope.not(RecordScope.in("region", Subject.attribute("missing"))),
                RecordScope.all(),
                RecordScope.none());

        for (AccessSubject subject : List.of(a, noRecord)) {
            for (RecordScope scope : scopes) {
                ScopeClause clause = compiler.compile(tenant, scope, subject, tenants.tableName());
                List<UUID> sql = jdbi.withHandle(h -> clause.bind(h.createQuery(
                                "SELECT _id FROM " + tenants.tableName() + " WHERE 1=1" + clause.and()))
                        .mapTo(UUID.class).list());
                List<UUID> memory = jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + tenants.tableName())
                                .mapToMap().list()).stream()
                        .filter(row -> evaluator.matches(tenant, scope, fields(row), subject, (t, id, s) -> false))
                        .map(row -> (UUID) row.get("_id"))
                        .toList();
                assertThat(sql).as(scope + " for " + subject).containsExactlyInAnyOrderElementsOf(memory);
            }
        }
    }

    @Test
    void subjectPlaceholdersFailClosedEvenUnderNot() {
        RecordScopeCompiler compiler = new RecordScopeCompiler(RecordPolicies.none(registry));
        ScopedEntity tenant = compiler.policies().entity(Tenant.class);
        AccessSubject noRecord = AccessSubject.user("x", Set.of("CUSTOMER"));

        assertThat(select(compiler.compile(tenant, RecordScope.not(RecordScope.eq("owner", Subject.recordId())),
                noRecord, tenants.tableName()))).isEmpty();
        assertThat(select(compiler.compile(tenant, RecordScope.eq("owner", Subject.recordId()),
                AccessSubject.system(), tenants.tableName()))).isEmpty();
    }

    @Test
    void viaInheritsTheTargetsPolicyForTheSameSubject() {
        RecordPolicies policies = new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("owner", Subject.recordId())),
                RecordAccessPolicy.forCatalog(Project.class).appliesTo("CUSTOMER")
                        .read(RecordScope.via("tenant"))));
        RecordScopeCompiler compiler = new RecordScopeCompiler(policies);

        ScopeClause clause = compiler.clause(Project.class, customerA(), AccessMode.READ, projects.tableName());
        List<UUID> visible = jdbi.withHandle(h -> clause.bind(h.createQuery(
                        "SELECT _id FROM " + projects.tableName() + " WHERE 1=1" + clause.and()))
                .mapTo(UUID.class).list());
        assertThat(visible).containsExactly(projectA);

        // the same subject without the role is unscoped
        assertThat(compiler.clause(Project.class, AccessSubject.user("s", Set.of("STAFF")), AccessMode.READ,
                projects.tableName()).isAll()).isTrue();
        // equal subjects compile to equal clauses (SSE shares the in-scope query between them)
        assertThat(compiler.clause(Project.class, customerA(), AccessMode.READ, projects.tableName()))
                .isEqualTo(clause);
    }

    private List<UUID> select(ScopeClause clause) {
        return jdbi.withHandle(h -> clause.bind(h.createQuery(
                        "SELECT _id FROM " + tenants.tableName() + " WHERE 1=1" + clause.and()))
                .mapTo(UUID.class).list());
    }

    private static Map<String, Object> fields(Map<String, Object> row) {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("owner", row.get("owner"));
        values.put("status", row.get("status"));
        values.put("region", row.get("region"));
        return values;
    }

    private void insertTenant(UUID id, UUID owner, UUID status, String region) {
        jdbi.useHandle(h -> {
            var u = h.createUpdate("INSERT INTO " + tenants.tableName()
                    + " (_id, _code, _description, _deletion_mark, _is_folder, _version, owner, status, region)"
                    + " VALUES (:id, :code, :d, false, false, 0, :owner, :status, :region)")
                    .bind("id", id).bind("code", id.toString().substring(0, 8)).bind("d", "t")
                    .bind("status", status).bind("region", region);
            SqlBind.nullable(u, "owner", owner);
            u.execute();
        });
    }

    private void insertProject(UUID id, UUID tenant) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + projects.tableName()
                        + " (_id, _code, _description, _deletion_mark, _is_folder, _version, tenant)"
                        + " VALUES (:id, :code, 'p', false, false, 0, :tenant)")
                .bind("id", id).bind("code", id.toString().substring(0, 8)).bind("tenant", tenant).execute());
    }
}
