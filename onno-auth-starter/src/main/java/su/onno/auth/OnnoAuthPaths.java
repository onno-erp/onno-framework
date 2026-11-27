package su.onno.auth;

import su.onno.auth.spi.AuthPathsContributor;

import java.util.LinkedHashSet;
import java.util.List;

/** Immutable, shared path policy for the default security chains and account-session guard. */
record OnnoAuthPaths(List<String> publicPaths, List<String> csrfIgnoredPaths) {

    static OnnoAuthPaths resolve(OnnoAuthProperties properties, List<AuthPathsContributor> contributors) {
        var publicPaths = new LinkedHashSet<>(properties.effectivePublicPaths());
        var csrfPaths = new LinkedHashSet<>(properties.effectiveCsrfIgnoredPaths());
        for (AuthPathsContributor contributor : contributors) {
            publicPaths.addAll(contributor.publicPaths());
            csrfPaths.addAll(contributor.csrfIgnoredPaths());
        }
        return new OnnoAuthPaths(List.copyOf(publicPaths), List.copyOf(csrfPaths));
    }
}
