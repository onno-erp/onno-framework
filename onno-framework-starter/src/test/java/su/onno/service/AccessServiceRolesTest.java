package su.onno.service;

import su.onno.access.EntityRight;
import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Role resolution for {@link AccessService}, covering issue #54: action/write authorization
 * 403'd privileged users whenever the injected {@link Principal} was not the authority-bearing
 * {@code Authentication}. Roles must then come from the {@code SecurityContext}. Rights come from
 * {@link RoleGrants}; no role (ADMIN included) passes implicitly.
 */
class AccessServiceRolesTest {

    enum TestRole implements RoleKey { RENTALS, FINANCE, ADMIN }

    static final class TravellerReport {}
    static final class BankAccount {}

    private static final RoleGrants GRANTS = RoleGrants.of(TestRole.class)
            .role(TestRole.RENTALS, r -> r.write(TravellerReport.class))
            .role(TestRole.FINANCE, r -> r.read(BankAccount.class));

    private final MetadataRegistry registry = registry();
    private final AccessService access = new AccessService(registry, GRANTS);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication token(String name, String... roles) {
        UserDetails user = User.withUsername(name).password("x").roles(roles).build();
        return new UsernamePasswordAuthenticationToken(user, "x", user.getAuthorities());
    }

    private static DocumentDescriptor rentalsDoc() {
        return new DocumentDescriptor("Traveller Reports", "Traveller Reports", "traveller_reports",
                TravellerReport.class, 9, true, "TR", "documents", List.of(), List.of());
    }

    private static MetadataRegistry registry() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(rentalsDoc());
        // logicalName ("Bank Accounts") deliberately differs from the route slug ("bank_accounts").
        registry.registerCatalog(new CatalogDescriptor(
                "Bank Accounts", "Bank Accounts", "bank_accounts", BankAccount.class,
                12, false, true, "BA-", "Rentals", List.of()));
        return registry;
    }

    @Test
    void entityPoliciesConstrainEveryRole() {
        EntityAccessPolicy deny = (roles, type, name, write) -> false;
        AccessService denied = new AccessService(registry, GRANTS, () -> List.of(deny));
        assertThat(denied.canRead(token("rentals", "RENTALS"), rentalsDoc())).isFalse();
        assertThat(denied.canWrite(token("rentals", "RENTALS"), rentalsDoc())).isFalse();
    }

    @Test
    void aRoleNamedAdminIsOrdinary() {
        assertThat(access.canRead(token("admin", "ADMIN"), rentalsDoc())).isFalse();
        assertThat(access.canRead(token("admin", "ADMIN"), "catalog", "bank_accounts")).isFalse();
        assertThat(access.hasAnyRole(token("admin", "ADMIN"), List.of("RENTALS"))).isFalse();
    }

    @Test
    void anApplicationWithoutGrantsLetsEveryUserDoEverything() {
        AccessService open = new AccessService(registry);
        assertThat(open.canWrite(token("anyone"), rentalsDoc())).isTrue();
        assertThat(open.canUnpost(token("anyone"), rentalsDoc())).isTrue();
    }

    @Test
    void anIdpRoleMappedWithFromIdpCountsAsTheDeclaredRoleEverywhere() {
        RoleGrants grants = RoleGrants.of(TestRole.class)
                .role(TestRole.FINANCE, r -> r.read(BankAccount.class).fromIdp("erp-finance"));
        AccessService mapped = new AccessService(registry, grants);
        Authentication sso = token("sso", "erp-finance");

        // The caller's roles carry the declared role, so plain role lists (commands, task
        // assignments, record policies, UI gates) match it, not only RoleGrants itself.
        assertThat(mapped.roles(sso)).contains("ERP-FINANCE", "FINANCE");
        assertThat(mapped.hasAnyRole(sso, List.of("finance"))).isTrue();
        assertThat(mapped.canRead(sso, "catalog", "bank_accounts")).isTrue();
        // Without grants nothing is mapped.
        assertThat(new AccessService(registry).roles(sso)).containsExactly("ERP-FINANCE");
    }

    @Test
    void anAnonymousCallerGetsNothingEvenWithoutGrants() {
        AccessService open = new AccessService(registry);
        assertThat(open.canRead((Principal) null, rentalsDoc())).isFalse();
        assertThat(open.canRead((Principal) null, "catalog", "bank_accounts")).isFalse();
        assertThat(open.access((Principal) null).can(TravellerReport.class, EntityRight.READ)).isFalse();
        assertThat(open.canRead(su.onno.access.AccessSubject.user(null, java.util.Set.of()), rentalsDoc())).isFalse();
        assertThat(open.canRead(su.onno.access.AccessSubject.system(), rentalsDoc())).isTrue();
    }

    @Test
    void postAndUnpostAreTheirOwnRights() {
        RoleGrants grants = RoleGrants.of(TestRole.class)
                .role(TestRole.RENTALS, r -> r.write(TravellerReport.class))
                .role(TestRole.FINANCE, r -> r.post(TravellerReport.class));
        AccessService posting = new AccessService(registry, grants);
        assertThat(posting.canWrite(token("r", "RENTALS"), rentalsDoc())).isTrue();
        assertThat(posting.canPost(token("r", "RENTALS"), rentalsDoc())).isFalse();
        assertThat(posting.canPost(token("f", "FINANCE"), rentalsDoc())).isTrue();
        assertThat(posting.canWrite(token("f", "FINANCE"), rentalsDoc())).as("post implies write").isTrue();
        assertThat(posting.canUnpost(token("f", "FINANCE"), rentalsDoc())).isFalse();
    }

    @Test
    void grantsMustFitTheModel() {
        assertThatThrownBy(() -> new AccessService(registry, RoleGrants.of(TestRole.class)
                .role(TestRole.RENTALS, r -> r.read(String.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a registered");
        assertThatThrownBy(() -> new AccessService(registry, RoleGrants.of(TestRole.class)
                .role(TestRole.FINANCE, r -> r.post(BankAccount.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only documents");
    }

    @Test
    void readsAuthoritiesFromAuthorityBearingPrincipal() {
        assertThat(access.roles(token("admin", "ADMIN", "RENTALS"))).contains("ADMIN", "RENTALS");
    }

    @Test
    void requireWriteAuthorizesViaInjectedPrincipal() {
        DocumentDescriptor desc = rentalsDoc();
        assertThatCode(() -> access.requireWrite(token("rentals", "RENTALS"), desc))
                .as("holds the write grant").doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireWrite(token("finance", "FINANCE"), desc))
                .hasMessageContaining("not allowed");
    }

    /**
     * The regression: when the injected principal carries no readable authorities, roles fall back
     * to the authenticated token in the {@code SecurityContext}, so privileged users are no longer
     * wrongly 403'd.
     */
    @Test
    void fallsBackToSecurityContextWhenPrincipalLacksAuthorities() {
        DocumentDescriptor desc = rentalsDoc();
        Principal bare = () -> "rentals"; // a bare java.security.Principal, no getAuthorities()

        SecurityContextHolder.getContext().setAuthentication(token("rentals", "RENTALS"));
        assertThat(access.roles(bare)).contains("RENTALS");
        assertThatCode(() -> access.requireWrite(bare, desc)).doesNotThrowAnyException();
    }

    @Test
    void deniesWhenNeitherPrincipalNorContextGrantsRole() {
        DocumentDescriptor desc = rentalsDoc();
        Principal bare = () -> "finance";

        SecurityContextHolder.getContext().setAuthentication(token("finance", "FINANCE"));
        assertThatThrownBy(() -> access.requireWrite(bare, desc)).hasMessageContaining("not allowed");
    }

    @Test
    void nullPrincipalAndEmptyContextResolvesToNoRoles() {
        assertThat(access.roles(null)).isEmpty();
    }

    /**
     * The #127 regression: the name-based read check (used by the comment endpoint, which passes a
     * route slug like {@code bank_accounts}) must resolve the same descriptor the detail routes do.
     * Names are matched normalized (space/underscore/case-insensitive), mirroring
     * {@code CatalogQueryService}.
     */
    @Test
    void nameBasedReadResolvesRouteSlugAcrossNameForms() {
        assertThat(access.canRead(token("finance", "FINANCE"), "catalog", "bank_accounts"))
                .as("holds the FINANCE read grant, descriptor resolved by normalized slug").isTrue();
        assertThat(access.canRead(token("rentals", "RENTALS"), "catalog", "bank_accounts"))
                .as("descriptor resolved but role denied — a real deny, not a not-found").isFalse();
        // ...as do the display-name and logical-name forms.
        assertThat(access.canRead(token("finance", "FINANCE"), "catalog", "Bank Accounts")).isTrue();
        // An unknown name still denies, even without grants.
        assertThat(new AccessService(registry).canRead(token("finance", "FINANCE"), "catalog", "nope")).isFalse();
        assertThat(access.access(token("finance", "FINANCE")).can(BankAccount.class, EntityRight.WRITE)).isFalse();
    }
}
