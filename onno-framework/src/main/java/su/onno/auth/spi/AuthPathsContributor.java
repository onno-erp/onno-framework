package su.onno.auth.spi;

import java.util.List;

/**
 * Additively contributes a starter's endpoint patterns to the default auth chains.
 * Register implementations as Spring beans. Patterns use the same syntax as the
 * corresponding {@code onno.auth.additional-public-paths} and
 * {@code onno.auth.additional-csrf-ignored-paths} properties.
 *
 * <p>Authentication and CSRF are independent: a public endpoint still requires a CSRF
 * token on mutations unless explicitly exempted. A CSRF exemption does not grant
 * anonymous access. Resource-server mode uses public paths but already disables CSRF.
 * Mandatory framework paths cannot be removed. No effect on application-owned chains.
 */
public interface AuthPathsContributor {

    /** Paths permitted without authentication. Never null. */
    default List<String> publicPaths() {
        return List.of();
    }

    /** Paths exempt from CSRF in cookie-based modes. Never null. */
    default List<String> csrfIgnoredPaths() {
        return List.of();
    }
}
