package su.onno.ui;

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
 * Role resolution for {@link UiAccessService}, covering issue #54: action/write authorization
 * 403'd privileged users (including {@code ADMIN}) whenever the injected {@link Principal} was not
 * the authority-bearing {@code Authentication}. Roles must then come from the {@code SecurityContext}.
 */
class UiAccessServiceRolesTest {

    private final UiAccessService access = new UiAccessService(null);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication token(String name, String... roles) {
        UserDetails user = User.withUsername(name).password("x").roles(roles).build();
        return new UsernamePasswordAuthenticationToken(user, "x", user.getAuthorities());
    }

    /** A document writable only by RENTALS, mirroring {@code @AccessControl(writeRoles = {"RENTALS"})}. */
    private static DocumentDescriptor rentalsDoc() {
        return new DocumentDescriptor("Traveller Reports", "Traveller Reports", "traveller_reports",
                Object.class, 9, true, "TR", "documents",
                List.of(), List.of("RENTALS"), List.of(), List.of());
    }

    @Test
    void readsAuthoritiesFromAuthorityBearingPrincipal() {
        assertThat(access.roles(token("admin", "ADMIN", "RENTALS"))).contains("ADMIN", "RENTALS");
    }

    @Test
    void requireWriteAuthorizesViaInjectedPrincipal() {
        DocumentDescriptor desc = rentalsDoc();
        assertThatCode(() -> access.requireWrite(token("admin", "ADMIN"), desc))
                .as("ADMIN superuser bypass").doesNotThrowAnyException();
        assertThatCode(() -> access.requireWrite(token("rentals", "RENTALS"), desc))
                .as("holds the required write role").doesNotThrowAnyException();
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
        Principal bare = () -> "admin"; // a bare java.security.Principal, no getAuthorities()

        SecurityContextHolder.getContext().setAuthentication(token("admin", "ADMIN"));
        assertThat(access.roles(bare)).contains("ADMIN");
        assertThatCode(() -> access.requireWrite(bare, desc)).doesNotThrowAnyException();
    }

    @Test
    void fallsBackForRequiredWriteRoleViaSecurityContext() {
        DocumentDescriptor desc = rentalsDoc();
        Principal bare = () -> "rentals";

        SecurityContextHolder.getContext().setAuthentication(token("rentals", "RENTALS"));
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
     * It matched {@code logicalName()} exactly, so a slug never equalled the logical name — the
     * descriptor was "not found" and the check 403'd every caller, ADMIN included. Names are now
     * matched normalized (space/underscore/case-insensitive), mirroring {@code CatalogQueryService}.
     */
    @Test
    void nameBasedReadResolvesRouteSlugAcrossNameForms() {
        MetadataRegistry registry = new MetadataRegistry();
        // logicalName ("Bank Accounts") deliberately differs from the route slug ("bank_accounts").
        registry.registerCatalog(new CatalogDescriptor(
                "Bank Accounts", "Bank Accounts", "bank_accounts", Object.class,
                12, false, true, "BA-", "Rentals",
                List.of("FINANCE"), List.of("FINANCE"), List.of()));
        UiAccessService withRegistry = new UiAccessService(registry);

        // The route slug resolves despite differing from logicalName...
        assertThat(withRegistry.canRead(token("admin", "ADMIN"), "catalog", "bank_accounts"))
                .as("ADMIN superuser, descriptor resolved by normalized slug").isTrue();
        assertThat(withRegistry.canRead(token("finance", "FINANCE"), "catalog", "bank_accounts"))
                .as("holds the FINANCE read role").isTrue();
        assertThat(withRegistry.canRead(token("rentals", "RENTALS"), "catalog", "bank_accounts"))
                .as("descriptor resolved but role denied — a real deny, not a not-found").isFalse();
        // ...as do the display-name and logical-name forms.
        assertThat(withRegistry.canRead(token("finance", "FINANCE"), "catalog", "Bank Accounts")).isTrue();
        // An unknown name still denies.
        assertThat(withRegistry.canRead(token("admin", "ADMIN"), "catalog", "nope")).isFalse();
    }
}
