package su.onno.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts Spring Security authorities from OIDC token claims, in a provider-agnostic way.
 *
 * <p>Roles are not a standard OIDC claim, so each IdP carries them differently. This converter is
 * driven by the resolved {@link OnnoAuthProperties.ResolvedOidc} role sources, each naming a claim
 * path and a {@link OnnoAuthProperties.Shape}:
 *
 * <ul>
 *   <li><b>Keycloak</b> — {@code realm_access} / {@code resource_access.<client>} wrappers, each an
 *       object containing a {@code roles} array ({@link OnnoAuthProperties.Shape#ARRAY}).</li>
 *   <li><b>Zitadel</b> — {@code urn:zitadel:iam:org:project:roles}, an object whose <em>keys</em> are
 *       the role names ({@link OnnoAuthProperties.Shape#OBJECT_KEYS}).</li>
 * </ul>
 *
 * <p>Both the OIDC-login and resource-server modes feed their claim maps through this converter so
 * role mapping is identical regardless of how the token arrived.
 */
final class ClaimRoleConverter {

    private final List<OnnoAuthProperties.RoleSource> sources;
    private final String prefix;

    ClaimRoleConverter(OnnoAuthProperties.ResolvedOidc oidc) {
        this.sources = oidc.roleSources();
        this.prefix = oidc.rolePrefix() == null ? "" : oidc.rolePrefix();
    }

    Collection<GrantedAuthority> convert(Map<String, Object> claims) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (claims == null) {
            return authorities;
        }
        for (OnnoAuthProperties.RoleSource source : sources) {
            Object node = resolve(claims, source.getClaim());
            if (node == null) {
                continue;
            }
            List<String> roles = switch (source.getShape()) {
                case ARRAY -> asArrayRoles(node);
                case OBJECT_KEYS -> asObjectKeyRoles(node);
            };
            addRoles(authorities, roles);
        }
        return authorities;
    }

    /**
     * Resolves a claim path. A literal top-level key is tried first — so single keys that contain
     * dots (Zitadel's {@code urn:zitadel:iam:org:project:roles}) work as-is — falling back to
     * dotted-path walking of nested maps (Keycloak's {@code realm_access.roles},
     * {@code resource_access.<client>}).
     */
    private static Object resolve(Map<String, Object> claims, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /** ARRAY shape: a {@code Collection} of role strings, or a map wrapping a {@code roles} collection. */
    @SuppressWarnings("unchecked")
    private static List<String> asArrayRoles(Object node) {
        if (node instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> wrapped) {
            return stringify(wrapped);
        }
        if (node instanceof Collection<?> collection) {
            return stringify(collection);
        }
        return List.of();
    }

    /** OBJECT_KEYS shape: a map whose keys are the role names. */
    private static List<String> asObjectKeyRoles(Object node) {
        if (node instanceof Map<?, ?> map) {
            return stringify(map.keySet());
        }
        return List.of();
    }

    private static List<String> stringify(Collection<?> values) {
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value != null) {
                result.add(value.toString());
            }
        }
        return result;
    }

    private void addRoles(Set<GrantedAuthority> authorities, List<String> roles) {
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(prefix + role));
            }
        }
    }
}
