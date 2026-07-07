package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class UiAccessService {

    /** The role that bypasses all per-entity read/write checks. */
    private static final String SUPERUSER_ROLE = "ADMIN";

    private final MetadataRegistry registry;

    public UiAccessService(MetadataRegistry registry) {
        this.registry = registry;
    }

    public boolean canRead(Principal principal, CatalogDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public boolean canWrite(Principal principal, CatalogDescriptor descriptor) {
        return hasAnyRole(principal, effectiveWriteRoles(descriptor.readRoles(), descriptor.writeRoles()));
    }

    public boolean canRead(Principal principal, DocumentDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public boolean canWrite(Principal principal, DocumentDescriptor descriptor) {
        return hasAnyRole(principal, effectiveWriteRoles(descriptor.readRoles(), descriptor.writeRoles()));
    }

    public boolean canRead(Principal principal, AccumulationRegisterDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public boolean canWrite(Principal principal, AccumulationRegisterDescriptor descriptor) {
        return hasAnyRole(principal, effectiveWriteRoles(descriptor.readRoles(), descriptor.writeRoles()));
    }

    public boolean canRead(Principal principal, InformationRegisterDescriptor descriptor) {
        return hasAnyRole(principal, descriptor.readRoles());
    }

    public void requireRead(Principal principal, CatalogDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("catalog", descriptor.logicalName());
    }

    public void requireWrite(Principal principal, CatalogDescriptor descriptor) {
        if (!canWrite(principal, descriptor)) throw forbidden("catalog", descriptor.logicalName());
    }

    public void requireRead(Principal principal, DocumentDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("document", descriptor.logicalName());
    }

    public void requireWrite(Principal principal, DocumentDescriptor descriptor) {
        if (!canWrite(principal, descriptor)) throw forbidden("document", descriptor.logicalName());
    }

    public void requireRead(Principal principal, AccumulationRegisterDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("register", descriptor.logicalName());
    }

    public void requireRead(Principal principal, InformationRegisterDescriptor descriptor) {
        if (!canRead(principal, descriptor)) throw forbidden("information register", descriptor.logicalName());
    }

    public boolean canRead(Principal principal, String type, String name) {
        return canRead(roles(principal), type, name);
    }

    /**
     * Write-access counterpart of {@link #canRead(Principal, String, String)}: resolves the entity
     * by kind + name (route segment or display name, normalized the same way) and checks the caller
     * against its effective write roles — write roles fall back to read roles when unset, the same
     * rule the descriptor overloads apply. Used to stamp {@code canWrite} into UI descriptors so
     * the client can hide write affordances (row Edit/Delete, kanban drag, related-list add) that
     * the REST layer would reject anyway.
     */
    public boolean canWrite(Principal principal, String type, String name) {
        Set<String> roles = roles(principal);
        String normalized = normalizeName(name);
        return switch (type) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst()
                    .map(d -> hasAnyRole(roles, effectiveWriteRoles(d.readRoles(), d.writeRoles())))
                    .orElse(false);
            case "document" -> registry.allDocuments().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst()
                    .map(d -> hasAnyRole(roles, effectiveWriteRoles(d.readRoles(), d.writeRoles())))
                    .orElse(false);
            case "register" -> registry.allRegisters().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst()
                    .map(d -> hasAnyRole(roles, effectiveWriteRoles(d.readRoles(), d.writeRoles())))
                    .orElse(false);
            default -> false;
        };
    }

    /**
     * Read-access check against a <em>pre-resolved</em> role set, for callers that capture the
     * subscriber's authorities up front and evaluate access off the request thread. The live SSE
     * stream ({@link UiEventPublisher}) fans events from the event-publishing / cluster-relay thread,
     * where {@code SecurityContextHolder} no longer holds the subscriber's authentication — so the
     * {@link Principal} overloads (which resolve roles from the in-flight request) can't be used there.
     * Capture roles with {@link #roles(Principal)} at subscribe time, then gate each event with this.
     *
     * <p>Semantics mirror {@link #canRead(Principal, String, String)} exactly: {name} arrives as the
     * route segment (e.g. "properties"), not the descriptor's display name ("Properties"), so it is
     * resolved the same case-/separator-insensitive way the generic controllers and query services do
     * (see {@code CatalogQueryService}) — otherwise a perfectly-readable entity is treated as unknown
     * just because its display name isn't already lower-cased (#127).
     */
    public boolean canRead(Set<String> roles, String type, String name) {
        String normalized = normalizeName(name);
        return switch (type) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst()
                    .map(d -> hasAnyRole(roles, d.readRoles()))
                    .orElse(false);
            case "document" -> registry.allDocuments().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst()
                    .map(d -> hasAnyRole(roles, d.readRoles()))
                    .orElse(false);
            case "register" -> registry.allRegisters().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst()
                    .map(d -> hasAnyRole(roles, d.readRoles()))
                    .orElse(false);
            default -> false;
        };
    }

    /**
     * Whether a live SSE event for {@code entityType}/{@code entityName} may be delivered to a
     * subscriber holding {@code roles} (#190). Modelled kinds (catalog/document/register) use the
     * per-entity read grant. A {@code comment} event is scoped to the commented record — a catalog or
     * document named {@code entityName} (see {@code CommentController}) — so it is authorized by that
     * record's read grant. Any other event type is delivered only to the {@code ADMIN} superuser:
     * fail closed, so a new event kind can't leak before this filter is taught to authorize it. (The
     * {@code presence} sentinel is authorized by the publisher, which maps the record kind itself.)
     */
    public boolean canReceiveEvent(Set<String> roles, String entityType, String entityName) {
        // A wildcard ("*") change event names no specific entity and carries no row data — it is a
        // "something of this type changed, refetch" nudge. Document posting emits
        // ("changed","register","*") so any open register surface refetches; the rows themselves are
        // always re-read through the RBAC-gated feed, so the bare nudge is safe for any authenticated
        // subscriber. Without this it resolved "*" as a (non-existent) entity name and fell to
        // deny-by-default, dropping the event for everyone — which is why an open register never
        // live-refreshed after a post (the balance only updated on a manual reload / re-navigation).
        if ("*".equals(entityName)) {
            return true;
        }
        return switch (entityType == null ? "" : entityType) {
            case "catalog", "document", "register" -> canRead(roles, entityType, entityName);
            case "comment" -> canRead(roles, "catalog", entityName) || canRead(roles, "document", entityName);
            // A page (dashboard / entity list / custom route) is not entity-scoped — its presence is
            // visible to any signed-in viewer (every SSE subscriber is authenticated). See the route model
            // in PresenceController.
            case "page" -> true;
            default -> roles != null && roles.contains(SUPERUSER_ROLE);
        };
    }

    /** Strip spaces/underscores and lower-case, matching the generic controllers' {name} lookup. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.replace(" ", "").replace("_", "").toLowerCase();
    }

    /**
     * The normalized roles granted to the caller. Authorities are read off the request's
     * {@code Authentication} reflectively, because this module deliberately does not depend on
     * Spring Security — only its runtime presence.
     *
     * <p>The {@link Principal} that Spring injects into a controller is not guaranteed to be the
     * authority-bearing {@code Authentication}: depending on the auth backend it can be a bare
     * {@link Principal}, a {@code UserDetails}/{@code OidcUser}, or otherwise expose no readable
     * {@code getAuthorities()}. When the injected principal yields nothing we fall back to the
     * authenticated token held in the {@code SecurityContext}, which is the canonical source of
     * authorities for the in-flight request. Without this fallback, write checks (the only callers
     * of {@code requireWrite}) 403 even privileged users, including {@code ADMIN}. See issue #54.
     */
    public Set<String> roles(Principal principal) {
        Set<String> roles = authoritiesOf(principal);
        if (roles.isEmpty()) {
            roles = authoritiesOf(currentAuthentication());
        }
        return roles;
    }

    /** Reflectively read {@code getAuthorities().getAuthority()} off any object, or empty if absent. */
    private static Set<String> authoritiesOf(Object source) {
        Set<String> roles = new LinkedHashSet<>();
        if (source == null) return roles;
        try {
            Object result = invokePublic(source, "getAuthorities");
            if (result instanceof Collection<?> collection) {
                for (Object authority : collection) {
                    Object value = invokePublic(authority, "getAuthority");
                    if (value instanceof String role) {
                        roles.add(normalizeRole(role));
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return roles;
    }

    /** The current request's {@code Authentication} from Spring Security's context, or null. */
    private static Object currentAuthentication() {
        try {
            Class<?> holder = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Object context = holder.getMethod("getContext").invoke(null);
            if (context == null) return null;
            // Invoke through the SecurityContext interface so a non-public context implementation
            // class doesn't trip an IllegalAccessException.
            Class<?> contextType = Class.forName("org.springframework.security.core.context.SecurityContext");
            return contextType.getMethod("getAuthentication").invoke(context);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Invoke a no-arg method, tolerating authority/authentication implementations whose concrete
     * class is not {@code public} (a public method on a package-private class otherwise throws
     * {@link IllegalAccessException}).
     */
    private static Object invokePublic(Object target, String method) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private boolean hasAnyRole(Principal principal, List<String> requiredRoles) {
        // Don't gate on principal != null: roles() resolves authorities from the SecurityContext
        // when the injected principal carries none (or is absent). Deny-by-default still applies —
        // an unauthenticated caller simply resolves to no roles.
        return hasAnyRole(roles(principal), requiredRoles);
    }

    private boolean hasAnyRole(Set<String> actualRoles, List<String> requiredRoles) {
        // ADMIN is the superuser: it sees and edits everything regardless of
        // per-entity role lists.
        if (actualRoles.contains(SUPERUSER_ROLE)) return true;
        // Deny by default: an entity with no explicit grant is invisible and
        // uneditable to everyone but the superuser. Access is opt-in, not opt-out.
        if (requiredRoles == null || requiredRoles.isEmpty()) return false;
        return requiredRoles.stream()
                .map(UiAccessService::normalizeRole)
                .anyMatch(actualRoles::contains);
    }

    private List<String> effectiveWriteRoles(List<String> readRoles, List<String> writeRoles) {
        return writeRoles == null || writeRoles.isEmpty() ? readRoles : writeRoles;
    }

    private static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private ResponseStatusException forbidden(String type, String name) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Current user is not allowed to access " + type + ": " + name);
    }
}
