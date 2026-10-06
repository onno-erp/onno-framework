package su.onno.access;

import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Dimension;
import su.onno.annotations.Document;
import su.onno.annotations.Enumeration;
import su.onno.annotations.Resource;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordPoliciesTest {

    @Enumeration(name = "RpTenantStatuses")
    public enum TenantStatus { ACTIVE, ARCHIVED }

    @Catalog(name = "RpOwners")
    @AccessControl(readRoles = {"CUSTOMER", "STAFF"})
    public static class Owner extends CatalogObject {
    }

    @Catalog(name = "RpTenants")
    @AccessControl(readRoles = {"CUSTOMER", "STAFF"})
    public static class Tenant extends CatalogObject {
        @Attribute
        private Ref<Owner> owner;
        @Attribute
        private TenantStatus status;
        @Attribute
        private String region;
        @Attribute(secret = true)
        private String apiToken;
    }

    @Document(name = "RpBuildJobs")
    @AccessControl(readRoles = {"CUSTOMER", "STAFF"})
    public static class BuildJob extends DocumentObject {
        @Attribute
        private Ref<Tenant> tenant;
    }

    @AccumulationRegister(name = "RpUsage", type = AccumulationType.TURNOVER)
    @AccessControl(readRoles = {"CUSTOMER", "STAFF"})
    public static class Usage extends AccumulationRecord {
        @Dimension
        private Ref<Tenant> tenant;
        @Resource
        private BigDecimal minutes;
    }

    @Catalog(name = "RpFolders", hierarchical = true)
    @AccessControl(readRoles = {"CUSTOMER"})
    public static class Folder extends CatalogObject {
    }

    @Catalog(name = "RpStaffOnly")
    @AccessControl(readRoles = {"STAFF"})
    public static class StaffOnly extends CatalogObject {
    }

    private MetadataRegistry registry;
    private final UUID ownerA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerEnumeration(scanner.scanEnumeration(TenantStatus.class));
        registry.registerCatalog(scanner.scan(Owner.class));
        registry.registerCatalog(scanner.scan(Tenant.class));
        registry.registerCatalog(scanner.scan(Folder.class));
        registry.registerCatalog(scanner.scan(StaffOnly.class));
        registry.registerDocument(scanner.scanDocument(BuildJob.class));
        registry.registerAccumulation(scanner.scanRegister(Usage.class));
    }

    private AccessSubject.User customer() {
        return new AccessSubject.User("a@example.com", Set.of("CUSTOMER"), ownerA, Map.of("regions", List.of("eu")));
    }

    private RecordPolicies policies(RecordAccessPolicy... policies) {
        return new RecordPolicies(registry, List.of(policies));
    }

    private static RecordAccessPolicy tenantsByOwner() {
        return RecordAccessPolicy.forCatalog(Tenant.class)
                .appliesTo("CUSTOMER")
                .read(RecordScope.eq("owner", Subject.recordId()))
                .defaults(d -> d.set("owner", Subject.recordId()));
    }

    // ------------------------------------------------------------------ applicability

    @Test
    void appliesOnlyToHoldersOfTheRoleAndAdminAndSystemAreExempt() {
        RecordPolicies p = policies(tenantsByOwner());

        assertThat(p.scopeFor(Tenant.class, customer(), AccessMode.READ))
                .isEqualTo(RecordScope.eq("owner", Subject.recordId()));
        assertThat(p.scopeFor(Tenant.class, AccessSubject.user("s", Set.of("STAFF")), AccessMode.READ))
                .isEqualTo(RecordScope.all());
        assertThat(p.scopeFor(Tenant.class, AccessSubject.user("root", Set.of("ADMIN", "CUSTOMER")), AccessMode.READ))
                .isEqualTo(RecordScope.all());
        assertThat(p.scopeFor(Tenant.class, AccessSubject.system(), AccessMode.READ))
                .isEqualTo(RecordScope.all());
        // an entity without a policy is unscoped
        assertThat(p.scopeFor(Owner.class, customer(), AccessMode.READ)).isEqualTo(RecordScope.all());
    }

    @Test
    void exemptRolesWinOverApplicability() {
        RecordPolicies p = policies(tenantsByOwner().exemptRoles("STAFF"));
        AccessSubject both = AccessSubject.user("x", Set.of("CUSTOMER", "STAFF"));

        assertThat(p.isScoped(Tenant.class, both, AccessMode.READ)).isFalse();
        assertThat(p.isScoped(Tenant.class, customer(), AccessMode.READ)).isTrue();
    }

    @Test
    void severalApplicablePoliciesAreOred() {
        RecordAccessPolicy byRegion = RecordAccessPolicy.forCatalog(Tenant.class)
                .appliesTo("CUSTOMER")
                .read(RecordScope.in("region", Subject.attribute("regions")));
        RecordPolicies p = policies(tenantsByOwner(), byRegion);

        assertThat(p.scopeFor(Tenant.class, customer(), AccessMode.READ)).isEqualTo(RecordScope.or(
                RecordScope.eq("owner", Subject.recordId()),
                RecordScope.in("region", Subject.attribute("regions"))));
    }

    @Test
    void writeScopeDefaultsToReadScope() {
        RecordPolicies defaulted = policies(tenantsByOwner());
        RecordPolicies readOnly = policies(tenantsByOwner().write(RecordScope.none()));

        assertThat(defaulted.scopeFor(Tenant.class, customer(), AccessMode.WRITE))
                .isEqualTo(RecordScope.eq("owner", Subject.recordId()));
        assertThat(readOnly.scopeFor(Tenant.class, customer(), AccessMode.WRITE)).isEqualTo(RecordScope.none());
    }

    @Test
    void defaultsResolveSubjectPlaceholders() {
        RecordPolicies p = policies(tenantsByOwner());

        assertThat(p.defaultsFor(Tenant.class, customer())).containsEntry("owner", ownerA);
        assertThat(p.defaultsFor(Tenant.class, AccessSubject.user("s", Set.of("STAFF")))).isEmpty();
        // no identity record → the default is omitted (and the write scope then fails closed)
        assertThat(p.defaultsFor(Tenant.class, AccessSubject.user("c", Set.of("CUSTOMER")))).isEmpty();
    }

    @Test
    void fingerprintIsEmptyForUnrestrictedSubjectsAndStablePerSubject() {
        RecordPolicies p = policies(tenantsByOwner());

        assertThat(p.fingerprint(AccessSubject.user("s", Set.of("STAFF")))).isEmpty();
        assertThat(p.fingerprint(customer())).isNotEmpty().isEqualTo(p.fingerprint(customer()));
        assertThat(p.fingerprint(customer()))
                .isNotEqualTo(p.fingerprint(customer().withRecordId(UUID.randomUUID())));
    }

    // ------------------------------------------------------------------ boot validation

    @Test
    void rejectsUnknownFieldsSecretFieldsAndBadVia() {
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
                .read(RecordScope.eq("nope", "x"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'nope'");
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
                .read(RecordScope.eq("apiToken", "x"))))
                .hasMessageContaining("'apiToken'");
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
                .read(RecordScope.via("region"))))
                .hasMessageContaining("not a reference field");
    }

    @Test
    void rejectsWrongKindMissingReadScopeAndDeadPolicies() {
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forDocument(Tenant.class).appliesTo("CUSTOMER")
                .read(RecordScope.all())))
                .hasMessageContaining("forDocument on a CATALOG");
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")))
                .hasMessageContaining("read(...) scope is required");
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(StaffOnly.class).appliesTo("CUSTOMER")
                .read(RecordScope.all())))
                .hasMessageContaining("dead");
        assertThatThrownBy(() -> policies(RecordAccessPolicy.of(String.class).appliesTo("CUSTOMER")
                .read(RecordScope.all())))
                .hasMessageContaining("not a registered");
    }

    @Test
    void rejectsLiteralsThatCanNeverMatch() {
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(Tenant.class).appliesTo("CUSTOMER")
                .read(RecordScope.eq("owner", "not-a-uuid"))))
                .hasMessageContaining("can never match");
    }

    @Test
    void registersMayOnlyScopeByDimensions() {
        policies(RecordAccessPolicy.forRegister(Usage.class).appliesTo("CUSTOMER").read(RecordScope.via("tenant")));
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forRegister(Usage.class).appliesTo("CUSTOMER")
                .read(RecordScope.eq("minutes", 1))))
                .hasMessageContaining("dimension");
    }

    @Test
    void rejectsInheritedViaCycles() {
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forCatalog(Folder.class).appliesTo("CUSTOMER")
                .read(RecordScope.or(RecordScope.isNull("parent"), RecordScope.via("parent")))))
                .hasMessageContaining("via cycle");
        // an explicit target scope is finite and fine
        policies(RecordAccessPolicy.forCatalog(Folder.class).appliesTo("CUSTOMER")
                .read(RecordScope.via("parent", RecordScope.eq("code", "root"))));
    }

    @Test
    void validatesNestedViaTargetScopes() {
        policies(tenantsByOwner(), RecordAccessPolicy.forDocument(BuildJob.class).appliesTo("CUSTOMER")
                .read(RecordScope.via("tenant")));
        assertThatThrownBy(() -> policies(RecordAccessPolicy.forDocument(BuildJob.class).appliesTo("CUSTOMER")
                .read(RecordScope.via("tenant", RecordScope.eq("nope", "x")))))
                .hasMessageContaining("'nope'");
    }

    // ------------------------------------------------------------------ in-memory evaluation

    @Test
    void evaluatorFollowsThreeValuedLogicAndFailsClosed() {
        RecordPolicies p = policies(tenantsByOwner());
        RecordScopeEvaluator eval = new RecordScopeEvaluator(p);
        ScopedEntity tenant = p.entity(Tenant.class);
        RecordScopeEvaluator.ViaCheck noVia = (t, id, s) -> false;

        Map<String, Object> mine = Map.of("owner", ownerA, "status", TenantStatus.ACTIVE);
        Map<String, Object> theirs = Map.of("owner", UUID.randomUUID());
        Map<String, Object> ownerless = new HashMap<>();

        RecordScope byOwner = RecordScope.eq("owner", Subject.recordId());
        assertThat(eval.matches(tenant, byOwner, mine, customer(), noVia)).isTrue();
        assertThat(eval.matches(tenant, byOwner, theirs, customer(), noVia)).isFalse();
        assertThat(eval.matches(tenant, byOwner, ownerless, customer(), noVia)).isFalse();

        // a subject without an identity record: unknown, also under NOT
        AccessSubject noRecord = AccessSubject.user("c", Set.of("CUSTOMER"));
        assertThat(eval.matches(tenant, byOwner, mine, noRecord, noVia)).isFalse();
        assertThat(eval.matches(tenant, RecordScope.not(byOwner), mine, noRecord, noVia)).isFalse();

        // enums compare by their stored id, whatever form the value takes
        RecordScope active = RecordScope.eq("status", TenantStatus.ACTIVE);
        assertThat(eval.matches(tenant, active, mine, customer(), noVia)).isTrue();
        UUID activeId = registry.getEnumerationDescriptor(TenantStatus.class).values().get(0).id();
        assertThat(eval.matches(tenant, active, Map.of("status", activeId.toString()), customer(), noVia)).isTrue();

        // collections: membership, empty is false, missing attribute is unknown
        RecordScope inRegions = RecordScope.in("region", Subject.attribute("regions"));
        assertThat(eval.matches(tenant, inRegions, Map.of("region", "eu"), customer(), noVia)).isTrue();
        assertThat(eval.matches(tenant, inRegions, Map.of("region", "us"), customer(), noVia)).isFalse();
        assertThat(eval.matches(tenant, inRegions, Map.of("region", "eu"), noRecord, noVia)).isFalse();
        assertThat(eval.matches(tenant, RecordScope.in("region", List.of()), Map.of("region", "eu"), customer(), noVia))
                .isFalse();
    }

    @Test
    void evaluatorDelegatesViaWithTheInheritedTargetScope() {
        RecordPolicies p = policies(tenantsByOwner(),
                RecordAccessPolicy.forDocument(BuildJob.class).appliesTo("CUSTOMER").read(RecordScope.via("tenant")));
        RecordScopeEvaluator eval = new RecordScopeEvaluator(p);
        UUID tenantId = UUID.randomUUID();
        RecordScope[] seen = new RecordScope[1];

        boolean ok = eval.matches(p.entity(BuildJob.class), RecordScope.via("tenant"), Map.of("tenant", tenantId),
                customer(), (target, id, scope) -> {
                    seen[0] = scope;
                    return id.equals(tenantId) && target.type() == Tenant.class;
                });

        assertThat(ok).isTrue();
        assertThat(seen[0]).isEqualTo(RecordScope.eq("owner", Subject.recordId()));
    }

    @Test
    void scopedFieldsListIndexCandidates() {
        RecordPolicies p = policies(tenantsByOwner(),
                RecordAccessPolicy.forRegister(Usage.class).appliesTo("CUSTOMER").read(RecordScope.via("tenant")));

        Map<ScopedEntity, Set<ScopedEntity.Field>> fields = p.scopedFields();
        assertThat(fields.get(p.entity(Tenant.class))).extracting(ScopedEntity.Field::column).containsExactly("owner");
        assertThat(fields.get(p.entity(Usage.class))).extracting(ScopedEntity.Field::column).containsExactly("tenant");
    }
}
