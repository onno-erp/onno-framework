package su.onno.ui;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UiAccessService#canRead(java.security.Principal, String, String)} resolves the route-style
 * {@code {name}} the same case-/separator-insensitive way the generic controllers do. Regression for
 * the comments endpoint 403'ing a readable entity: the SPA addresses a thread by the lower-cased
 * route segment ("properties"), but the descriptor's display name is "Properties", so a strict
 * equality match wrongly denied access to every entity whose name wasn't already normalized.
 */
class UiAccessServiceNameLookupTest {

    private static Authentication admin() {
        UserDetails user = User.withUsername("admin").password("x").roles("ADMIN").build();
        return new UsernamePasswordAuthenticationToken(user, "x", user.getAuthorities());
    }

    private static CatalogDescriptor catalog(String logicalName, Class<?> javaClass) {
        return new CatalogDescriptor(logicalName, logicalName, logicalName.replace(" ", "_").toLowerCase(),
                javaClass, 9, false, true, "C", "Sales",
                List.of("RENTALS"), List.of(), List.of());
    }

    private UiAccessService withCatalogs() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(catalog("Properties", Object.class));
        registry.registerCatalog(catalog("Bank Accounts", String.class));
        return new UiAccessService(registry);
    }

    @Test
    void resolvesNameCaseInsensitively() {
        UiAccessService access = withCatalogs();
        assertThat(access.canRead(admin(), "catalog", "properties")).isTrue();
        assertThat(access.canRead(admin(), "catalog", "Properties")).isTrue();
        assertThat(access.canRead(admin(), "catalog", "PROPERTIES")).isTrue();
    }

    @Test
    void resolvesNameIgnoringSpacesAndUnderscores() {
        UiAccessService access = withCatalogs();
        assertThat(access.canRead(admin(), "catalog", "bank_accounts")).isTrue();
        assertThat(access.canRead(admin(), "catalog", "bankaccounts")).isTrue();
    }

    @Test
    void deniesAnUnknownName() {
        UiAccessService access = withCatalogs();
        // ADMIN is a superuser, so a true here would mean access slipped through without the entity
        // even resolving — an unknown name must still be denied.
        assertThat(access.canRead(admin(), "catalog", "nonexistent")).isFalse();
    }
}
