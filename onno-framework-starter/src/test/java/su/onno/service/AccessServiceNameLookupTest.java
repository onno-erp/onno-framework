package su.onno.service;

import su.onno.access.RoleGrants;
import su.onno.access.RoleKey;
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
 * {@link AccessService#canRead(java.security.Principal, String, String)} resolves the route-style
 * {@code {name}} the same case-/separator-insensitive way the generic controllers do. Regression for
 * the comments endpoint 403'ing a readable entity: the SPA addresses a thread by the lower-cased
 * route segment ("properties"), but the descriptor's display name is "Properties", so a strict
 * equality match wrongly denied access to every entity whose name wasn't already normalized.
 */
class AccessServiceNameLookupTest {

    enum TestRole implements RoleKey { RENTALS }

    static final class Property {}
    static final class BankAccount {}

    private static Authentication rentals() {
        UserDetails user = User.withUsername("rentals").password("x").roles("RENTALS").build();
        return new UsernamePasswordAuthenticationToken(user, "x", user.getAuthorities());
    }

    private static CatalogDescriptor catalog(String logicalName, Class<?> javaClass) {
        return new CatalogDescriptor(logicalName, logicalName, logicalName.replace(" ", "_").toLowerCase(),
                javaClass, 9, false, true, "C", "Sales", List.of());
    }

    private AccessService withCatalogs() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(catalog("Properties", Property.class));
        registry.registerCatalog(catalog("Bank Accounts", BankAccount.class));
        return new AccessService(registry, RoleGrants.of(TestRole.class)
                .role(TestRole.RENTALS, r -> r.read(Property.class, BankAccount.class)));
    }

    @Test
    void resolvesNameCaseInsensitively() {
        AccessService access = withCatalogs();
        assertThat(access.canRead(rentals(), "catalog", "properties")).isTrue();
        assertThat(access.canRead(rentals(), "catalog", "Properties")).isTrue();
        assertThat(access.canRead(rentals(), "catalog", "PROPERTIES")).isTrue();
    }

    @Test
    void resolvesNameIgnoringSpacesAndUnderscores() {
        AccessService access = withCatalogs();
        assertThat(access.canRead(rentals(), "catalog", "bank_accounts")).isTrue();
        assertThat(access.canRead(rentals(), "catalog", "bankaccounts")).isTrue();
    }

    @Test
    void deniesAnUnknownName() {
        // Even in an application without grants (everyone reads everything), an unknown name must
        // be denied: a true here would mean access slipped through without the entity resolving.
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(catalog("Properties", Property.class));
        assertThat(new AccessService(registry).canRead(rentals(), "catalog", "nonexistent")).isFalse();
        assertThat(withCatalogs().canRead(rentals(), "catalog", "nonexistent")).isFalse();
    }
}
