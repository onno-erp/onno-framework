package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.access.RecordAccessPolicy;
import su.onno.access.RecordPolicies;
import su.onno.access.RecordScope;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecordAccess#canReadRecord} and {@link RecordAccess#canReadRoute} fail closed (#462): a
 * missing subject throws and a non-UUID id is denied, with or without policies; for a subject some
 * policy narrows, an unknown entity is denied too. Subjects no policy narrows (ADMIN, system, uncovered
 * roles) are never narrowed. A list or {@code new} route carries no record id, so {@code canReadRoute}
 * still allows it.
 */
class RecordAccessTest {

    @AccessControl(readRoles = {"CUSTOMER", "STAFF"})
    @Catalog(name = "RaTenants", previousNames = "RaLessees")
    static class Tenant extends CatalogObject {
        @Attribute(length = 20)
        private String region;
    }

    @AccessControl(readRoles = {"CUSTOMER", "STAFF"})
    @Document(name = "RaOrders")
    static class Order extends DocumentObject {
    }

    private static final AccessSubject CUSTOMER = AccessSubject.user("c@ra.test", Set.of("CUSTOMER"));
    private static final AccessSubject STAFF = AccessSubject.user("s@ra.test", Set.of("STAFF"));
    private static final AccessSubject ADMIN = AccessSubject.user("a@ra.test", Set.of("ADMIN"));

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private CatalogDescriptor tenants;
    private RecordAccess access;
    private final UUID tenantEu = UUID.randomUUID();
    private final UUID tenantUs = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ra" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        tenants = scanner.scan(Tenant.class);
        registry.registerCatalog(tenants);
        registry.registerDocument(scanner.scanDocument(Order.class));
        new SchemaGenerator(registry).execute(jdbi);

        insertTenant(tenantEu, "eu");
        insertTenant(tenantUs, "us");

        // CUSTOMER sees only EU tenants; STAFF is not covered by the policy, so it is unscoped.
        access = recordAccess(new RecordPolicies(registry, List.of(
                RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
                        .read(RecordScope.eq("region", "eu")))));
    }

    @Test
    void notificationSqlMatchesRouteVisibilityIncludingAliasesAndUnknownEntities() {
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE notification_scope_test (_id INT, _target_kind VARCHAR, _target_name VARCHAR, _target_id UUID)");
            h.execute("INSERT INTO notification_scope_test VALUES (1,NULL,NULL,NULL),"
                    + "(2,'catalogs','ratenants',?),(3,'catalogs','ratenants',?),"
                    + "(4,'catalogs','ralessees',?),(5,'catalogs','unknown',?)", tenantEu, tenantUs, tenantEu, tenantEu);
        });
        ScopeClause visible = access.notificationScope(CUSTOMER);
        var ids = jdbi.withHandle(h -> visible.bind(h.createQuery(
                "SELECT _id FROM notification_scope_test WHERE " + visible.sql() + " ORDER BY _id")).mapTo(Integer.class).list());
        assertThat(ids).containsExactly(1, 2, 4);
        assertThat(access.notificationScope(STAFF).isAll()).isTrue();
        assertThatThrownBy(() -> access.notificationScope(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullSubjectAlwaysThrows() {
        RecordAccess unrestricted = recordAccess(RecordPolicies.none(registry));
        for (RecordAccess ra : List.of(access, unrestricted)) {
            assertThatThrownBy(() -> ra.canReadRecord(null, "catalogs", "RaTenants", tenantEu.toString()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ra.canReadRecord(null, "catalogs", "RaTenants", "new"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ra.canReadRecord(null, "catalogs", "Nope", tenantEu.toString()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anUnknownEntityIsDeniedForANarrowedSubject() {
        String id = UUID.randomUUID().toString();
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "Nope", id)).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, "documents", "Nope", id)).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", null, id)).isFalse();
    }

    @Test
    void anUnknownEntityIsNotNarrowedForASubjectNoPolicyNarrows() {
        // ADMIN and system are exempt and STAFF is not covered: a link to a removed entity stays theirs.
        String id = UUID.randomUUID().toString();
        for (AccessSubject subject : List.of(STAFF, ADMIN, AccessSubject.system())) {
            assertThat(access.canReadRecord(subject, "catalogs", "Nope", id)).as("%s", subject).isTrue();
            assertThat(access.canReadRecord(subject, "documents", "Nope", id)).as("%s", subject).isTrue();
        }
    }

    @Test
    void aFormerEntityNameStillResolves() {
        // A task subject or notification stored before a rename carries the previousNames entry.
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "RaLessees", tenantEu.toString())).isTrue();
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "RaLessees", tenantUs.toString())).isFalse();
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/RaLessees/" + tenantEu)).isTrue();
        assertThat(access.canReadRoute(ADMIN, "catalogs/RaLessees/" + tenantUs)).isTrue();
    }

    @Test
    void anUnknownEntityIsDeniedEvenWithANonUuidId() {
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "Nope", "new")).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "Nope", null)).isFalse();
    }

    @Test
    void anEntityOfAnotherKindIsUnknown() {
        // A catalog name under the documents kind, or a kind that is not a record surface at all.
        assertThat(access.canReadRecord(CUSTOMER, "documents", "RaTenants", tenantEu.toString())).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, "registers", "RaTenants", tenantEu.toString())).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, "page", "RaTenants", tenantEu.toString())).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, null, "RaTenants", tenantEu.toString())).isFalse();
    }

    @Test
    void aNameIsMatchedAsGivenNotDecodedAgain() {
        // canReadRecord takes the decoded logical name: escapes in it are literal characters.
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "Ra%20Tenants", tenantEu.toString())).isFalse();
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "100%", tenantEu.toString())).isFalse();
    }

    @Test
    void aNonUuidIdAddressesNoRecordAndIsDenied() {
        // A number or code is not a record id: it must not pass as readable for a lookup by it, and
        // the answer must not change when the first policy is declared.
        RecordAccess unrestricted = recordAccess(RecordPolicies.none(registry));
        for (RecordAccess ra : List.of(access, unrestricted)) {
            for (AccessSubject subject : List.of(CUSTOMER, STAFF, ADMIN, AccessSubject.system())) {
                assertThat(ra.canReadRecord(subject, "catalogs", "RaTenants", "SO-001")).as("%s", subject).isFalse();
                assertThat(ra.canReadRecord(subject, "catalogs", "RaTenants", "new")).as("%s", subject).isFalse();
                assertThat(ra.canReadRecord(subject, "catalogs", "RaTenants", "/catalogs/RaTenants"))
                        .as("%s", subject).isFalse();
                assertThat(ra.canReadRecord(subject, "catalogs", "RaTenants", null)).as("%s", subject).isFalse();
            }
        }
    }

    @Test
    void theRecordScopeStillDecidesForAScopedSubject() {
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "RaTenants", tenantEu.toString())).isTrue();
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "RaTenants", tenantUs.toString())).isFalse();
        // A record that does not exist is not readable either.
        assertThat(access.canReadRecord(CUSTOMER, "catalogs", "RaTenants", UUID.randomUUID().toString()))
                .isFalse();
    }

    @Test
    void theEntityNameResolvesInEveryRouteSpelling() {
        for (String name : List.of("RaTenants", "ra_tenants", "ratenants", "Ra Tenants")) {
            assertThat(access.canReadRecord(CUSTOMER, "catalogs", name, tenantEu.toString())).as(name).isTrue();
            assertThat(access.canReadRecord(CUSTOMER, "catalogs", name, tenantUs.toString())).as(name).isFalse();
        }
        // A route segment is percent-decoded once, by canReadRoute.
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/Ra%20Tenants/" + tenantEu)).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/Ra%20Tenants/" + tenantUs)).isFalse();
    }

    @Test
    void unscopedSubjectsAndSuperusersAreNotNarrowed() {
        for (AccessSubject subject : List.of(STAFF, ADMIN, AccessSubject.system())) {
            assertThat(access.canReadRecord(subject, "catalogs", "RaTenants", tenantUs.toString()))
                    .as("%s", subject).isTrue();
        }
        // An entity no policy covers is not narrowed for anyone.
        assertThat(access.canReadRecord(CUSTOMER, "documents", "RaOrders", UUID.randomUUID().toString()))
                .isTrue();
    }

    @Test
    void withoutAnyPolicyNoRecordIsNarrowed() {
        RecordAccess unrestricted = recordAccess(RecordPolicies.none(registry));
        String id = tenantUs.toString();

        assertThat(unrestricted.canReadRecord(CUSTOMER, "catalogs", "Nope", id)).isTrue();
        assertThat(unrestricted.canReadRecord(CUSTOMER, "catalogs", "100%", id)).isTrue();
        assertThat(unrestricted.canReadRecord(CUSTOMER, "catalogs", "RaTenants", id)).isTrue();
        assertThat(unrestricted.canReadRoute(CUSTOMER, "catalogs/Nope/" + id)).isTrue();
    }

    @Test
    void routesThatAreNotCatalogOrDocumentRecordsAreAllowed() {
        assertThat(access.canReadRoute(CUSTOMER, null)).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "/")).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "/reports/2026/q1")).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "registers/RaStock/" + tenantUs)).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/RaTenants")).isTrue();
        // A list link focused on a record is still a list route, and a draft has no record yet.
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/RaTenants?focus=" + tenantUs)).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/RaTenants/new")).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "documents/RaOrders/new")).isTrue();
    }

    @Test
    void aRecordRouteFollowsTheRecordScope() {
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/RaTenants/" + tenantEu)).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "/catalogs/RaTenants/" + tenantEu + "#comments")).isTrue();
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/RaTenants/" + tenantUs)).isFalse();
        assertThat(access.canReadRoute(CUSTOMER, "/catalogs/RaTenants/" + tenantUs + "?tab=history")).isFalse();
        assertThat(access.canReadRoute(STAFF, "catalogs/RaTenants/" + tenantUs)).isTrue();
    }

    @Test
    void aRecordRouteOfAnUnknownEntityIsDeniedToANarrowedSubject() {
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/Nope/" + tenantEu)).isFalse();
        assertThat(access.canReadRoute(CUSTOMER, "documents/Nope/" + tenantEu)).isFalse();
        // A malformed escape names no entity, and does not throw.
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/100%/" + tenantEu)).isFalse();
        assertThat(access.canReadRoute(CUSTOMER, "catalogs/%zz/" + tenantEu)).isFalse();
        assertThat(access.canReadRoute(ADMIN, "documents/Nope/" + tenantEu)).isTrue();
        assertThat(access.canReadRoute(ADMIN, "catalogs/100%/" + tenantEu)).isTrue();
    }

    @Test
    void aRecordRouteWithANullSubjectThrows() {
        assertThatThrownBy(() -> access.canReadRoute(null, "catalogs/RaTenants/" + tenantEu))
                .isInstanceOf(IllegalArgumentException.class);
        // A page route has no record to check, so it needs no subject.
        assertThat(access.canReadRoute(null, "/reports/2026/q1")).isTrue();
    }

    @Test
    void readableFiltersManyRoutesByTheSameRule() {
        RecordRoute eu = new RecordRoute("catalogs", "RaTenants", tenantEu);
        RecordRoute us = new RecordRoute("catalogs", "ra_tenants", tenantUs);
        RecordRoute former = new RecordRoute("catalogs", "RaLessees", tenantEu);
        RecordRoute missing = new RecordRoute("catalogs", "RaTenants", UUID.randomUUID());
        RecordRoute unscopedEntity = new RecordRoute("documents", "RaOrders", UUID.randomUUID());
        RecordRoute unknown = new RecordRoute("catalogs", "Nope", tenantEu);
        List<RecordRoute> all = List.of(eu, us, former, missing, unscopedEntity, unknown);

        assertThat(access.readable(CUSTOMER, all)).containsExactlyInAnyOrder(eu, former, unscopedEntity);
        for (AccessSubject subject : List.of(STAFF, ADMIN, AccessSubject.system())) {
            assertThat(access.readable(subject, all)).as("%s", subject).containsExactlyInAnyOrderElementsOf(all);
        }
        assertThat(access.readable(CUSTOMER, List.of())).isEmpty();
        assertThatThrownBy(() -> access.readable(null, all)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void narrowsReadsOnlyForSubjectsAPolicyCovers() {
        assertThat(access.narrowsReads(CUSTOMER)).isTrue();
        assertThat(access.narrowsReads(STAFF)).isFalse();
        assertThat(access.narrowsReads(ADMIN)).isFalse();
        assertThat(access.narrowsReads(AccessSubject.system())).isFalse();
        assertThat(recordAccess(RecordPolicies.none(registry)).narrowsReads(CUSTOMER)).isFalse();
    }

    private RecordAccess recordAccess(RecordPolicies policies) {
        return new RecordAccess(new RecordScopeCompiler(policies), new AccessService(registry), jdbi, false);
    }

    private void insertTenant(UUID id, String region) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + tenants.tableName()
                        + " (_id, _code, _description, region) VALUES (:id, :code, 't', :region)")
                .bind("id", id).bind("code", id.toString().substring(0, 8)).bind("region", region).execute());
    }
}
