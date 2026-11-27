package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.access.EffectiveAccess;
import su.onno.access.EntityRight;
import su.onno.access.Permission;
import su.onno.access.RoleGrants;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Entity-level access for every user-facing surface (REST, UI, MCP, import, events): what the
 * application's {@link RoleGrants} let the caller's roles do with an entity, narrowed by the
 * deny-only {@link EntityAccessPolicy} beans, plus the capability {@link Permission}s the roles hold.
 *
 * <p>There is no superuser: a user may do exactly what the roles they hold grant. Trusted code
 * ({@link AccessSubject#system()}) is not checked. An application that declares no grants runs
 * {@link RoleGrants#open()}, where every signed-in user may do everything.
 *
 * <p>Entity grants are keyed by the entity's Java class ({@code descriptor.javaClass()}); the
 * {@code (type, name)} overloads resolve the descriptor by route segment or display name first,
 * and an unknown entity is never accessible.
 */
public class AccessService {

    private final MetadataRegistry registry;
    private final RoleGrants grants;
    private final Supplier<List<EntityAccessPolicy>> policies;

    /**
     * @param grants   the application's grants; validated against {@code registry} here, so an
     *                 invalid grant fails startup
     * @param policies the deny-only entity policies, consulted on every check
     */
    public AccessService(MetadataRegistry registry, RoleGrants grants, Supplier<List<EntityAccessPolicy>> policies) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.policies = Objects.requireNonNull(policies, "policies");
        validate(registry, grants);
    }

    public AccessService(MetadataRegistry registry, RoleGrants grants) {
        this(registry, grants, List::of);
    }

    /** An application without grants: every signed-in user may do everything. */
    public AccessService(MetadataRegistry registry) {
        this(registry, RoleGrants.open());
    }

    /** The application's grants. */
    public RoleGrants grants() {
        return grants;
    }

    // ---------------------------------------------------------------- effective access / permissions

    /** What a user holding {@code roles} may do. */
    public EffectiveAccess access(Set<String> roles) {
        return grants.resolve(roles);
    }

    /** What {@code subject} may do (everything for trusted code). */
    public EffectiveAccess access(AccessSubject subject) {
        return anonymous(subject) ? EffectiveAccess.NONE : grants.resolve(subject);
    }

    /**
     * What the caller of the current request may do: nothing for an anonymous caller (no signed-in
     * user), even in an application without grants.
     */
    public EffectiveAccess access(Principal principal) {
        return signedIn(principal) ? grants.resolve(roles(principal)) : EffectiveAccess.NONE;
    }

    /** Whether the caller holds {@code permission}. */
    public boolean has(Principal principal, Permission permission) {
        return access(principal).has(permission);
    }

    /** Whether {@code subject} holds {@code permission} (trusted code always does). */
    public boolean has(AccessSubject subject, Permission permission) {
        return access(subject).has(permission);
    }

    // ---------------------------------------------------------------- Principal + descriptor

    public boolean canRead(Principal principal, CatalogDescriptor descriptor) {
        return can(principal, "catalog", descriptor.logicalName(), descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canWrite(Principal principal, CatalogDescriptor descriptor) {
        return can(principal, "catalog", descriptor.logicalName(), descriptor.javaClass(), EntityRight.WRITE);
    }

    public boolean canRead(Principal principal, DocumentDescriptor descriptor) {
        return can(principal, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canWrite(Principal principal, DocumentDescriptor descriptor) {
        return can(principal, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.WRITE);
    }

    public boolean canRead(Principal principal, AccumulationRegisterDescriptor descriptor) {
        return can(principal, "register", descriptor.logicalName(), descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canWrite(Principal principal, AccumulationRegisterDescriptor descriptor) {
        return can(principal, "register", descriptor.logicalName(), descriptor.javaClass(), EntityRight.WRITE);
    }

    public boolean canRead(Principal principal, InformationRegisterDescriptor descriptor) {
        return can(principal, "information register", descriptor.logicalName(), descriptor.javaClass(),
                EntityRight.READ);
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

    // ---------------------------------------------------------------- kind + name

    public boolean canRead(Principal principal, String type, String name) {
        return signedIn(principal) && canRead(roles(principal), type, name);
    }

    /**
     * Write-access counterpart of {@link #canRead(Principal, String, String)}: resolves the entity
     * by kind + name (route segment or display name, normalized the same way) and checks the caller's
     * write grant. Used to stamp {@code canWrite} into UI descriptors so the client can hide write
     * affordances (row Edit/Delete, kanban drag, related-list add) that the REST layer would reject
     * anyway.
     */
    public boolean canWrite(Principal principal, String type, String name) {
        return signedIn(principal) && canWrite(roles(principal), type, name);
    }

    /**
     * As {@link #canWrite(Principal, String, String)} against a pre-resolved role set. Information
     * registers have no generic write path and are never writable here.
     */
    public boolean canWrite(Set<String> roles, String type, String name) {
        if ("information register".equals(type)) return false;
        Class<?> javaClass = entityClass(type, name);
        return javaClass != null && can(roles, type, name, javaClass, EntityRight.WRITE);
    }

    /**
     * Read-access check against a <em>pre-resolved</em> role set, for callers that capture the
     * subscriber's authorities up front and evaluate access off the request thread. The live SSE
     * stream ({@code su.onno.web.EventPublisher}) fans events from the event-publishing / cluster-relay thread,
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
        Class<?> javaClass = entityClass(type, name);
        return javaClass != null && can(roles, type, name, javaClass, EntityRight.READ);
    }

    // ---------------------------------------------------------------- AccessSubject overloads
    //
    // The record-scoped services take an AccessSubject instead of a Principal. The trusted
    // AccessSubject.system() passes every entity-level check; a user is checked by its roles exactly
    // like the Principal overloads.

    /** Entity-level read grant for {@code subject} on the entity of {@code type}/{@code name}. */
    public boolean canRead(AccessSubject subject, String type, String name) {
        return subject.trusted() || (!anonymous(subject) && canRead(subject.roles(), type, name));
    }

    /** Entity-level write grant for {@code subject} on the entity of {@code type}/{@code name}. */
    public boolean canWrite(AccessSubject subject, String type, String name) {
        return subject.trusted() || (!anonymous(subject) && canWrite(subject.roles(), type, name));
    }

    public void requireRead(AccessSubject subject, CatalogDescriptor descriptor) {
        if (!canRead(subject, descriptor)) throw forbidden("catalog", descriptor.logicalName());
    }

    public void requireWrite(AccessSubject subject, CatalogDescriptor descriptor) {
        if (!canWrite(subject, descriptor)) throw forbidden("catalog", descriptor.logicalName());
    }

    public void requireRead(AccessSubject subject, DocumentDescriptor descriptor) {
        if (!canRead(subject, descriptor)) throw forbidden("document", descriptor.logicalName());
    }

    public void requireWrite(AccessSubject subject, DocumentDescriptor descriptor) {
        if (!canWrite(subject, descriptor)) throw forbidden("document", descriptor.logicalName());
    }

    public void requireRead(AccessSubject subject, AccumulationRegisterDescriptor descriptor) {
        if (!canRead(subject, descriptor)) throw forbidden("register", descriptor.logicalName());
    }

    public void requireRead(AccessSubject subject, InformationRegisterDescriptor descriptor) {
        if (!canRead(subject, descriptor)) throw forbidden("information register", descriptor.logicalName());
    }

    public boolean canRead(AccessSubject subject, AccumulationRegisterDescriptor descriptor) {
        return can(subject, "register", descriptor.logicalName(), descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canRead(AccessSubject subject, InformationRegisterDescriptor descriptor) {
        return can(subject, "information register", descriptor.logicalName(),
                descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canRead(AccessSubject subject, CatalogDescriptor descriptor) {
        return can(subject, "catalog", descriptor.logicalName(), descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canRead(AccessSubject subject, DocumentDescriptor descriptor) {
        return can(subject, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.READ);
    }

    public boolean canWrite(AccessSubject subject, CatalogDescriptor descriptor) {
        return can(subject, "catalog", descriptor.logicalName(), descriptor.javaClass(), EntityRight.WRITE);
    }

    public boolean canWrite(AccessSubject subject, DocumentDescriptor descriptor) {
        return can(subject, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.WRITE);
    }

    // ---------------------------------------------------------------- post / unpost (#546)
    //
    // Posting and unposting are their own entity rights (RoleGrants post(...)/unpost(...)), each of
    // which implies write and read. Trusted system code always passes.

    /** Whether {@code subject} may post / re-post documents of this type. */
    public boolean canPost(AccessSubject subject, DocumentDescriptor descriptor) {
        return can(subject, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.POST);
    }

    /** Whether {@code subject} may unpost documents of this type. */
    public boolean canUnpost(AccessSubject subject, DocumentDescriptor descriptor) {
        return can(subject, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.UNPOST);
    }

    public boolean canPost(Principal principal, DocumentDescriptor descriptor) {
        return can(principal, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.POST);
    }

    public boolean canUnpost(Principal principal, DocumentDescriptor descriptor) {
        return can(principal, "document", descriptor.logicalName(), descriptor.javaClass(), EntityRight.UNPOST);
    }

    public void requirePost(AccessSubject subject, DocumentDescriptor descriptor) {
        requireWrite(subject, descriptor);
        if (!canPost(subject, descriptor)) throw forbiddenOperation("post", descriptor.logicalName());
    }

    public void requireUnpost(AccessSubject subject, DocumentDescriptor descriptor) {
        requireWrite(subject, descriptor);
        if (!canUnpost(subject, descriptor)) throw forbiddenOperation("unpost", descriptor.logicalName());
    }

    // ---------------------------------------------------------------- role lists (command .roles)

    /**
     * Whether {@code subject} holds any of {@code requiredRoles}, compared normalized; trusted code
     * always passes. Only for explicit role lists such as a command's {@code .roles(...)} — entity
     * rights and capabilities come from {@link RoleGrants}. No role passes implicitly, and an empty
     * list matches no user: callers with an optional list skip the check when it is unset.
     */
    public boolean hasAnyRole(AccessSubject subject, List<String> requiredRoles) {
        return subject.trusted() || hasAnyRole(subject.roles(), requiredRoles);
    }

    /** As {@link #hasAnyRole(AccessSubject, List)} for the caller of the current request. */
    public boolean hasAnyRole(Principal principal, List<String> requiredRoles) {
        // Don't gate on principal != null: roles() resolves authorities from the SecurityContext
        // when the injected principal carries none (or is absent). An unauthenticated caller simply
        // resolves to no roles.
        return hasAnyRole(roles(principal), requiredRoles);
    }

    private static boolean hasAnyRole(Set<String> actualRoles, List<String> requiredRoles) {
        if (requiredRoles == null || requiredRoles.isEmpty()) return false;
        return requiredRoles.stream()
                .map(AccessSubject::normalizeRole)
                .anyMatch(actualRoles::contains);
    }

    // ---------------------------------------------------------------- live events

    /**
     * Whether a live SSE event for {@code entityType}/{@code entityName} may be delivered to a
     * subscriber holding {@code roles} (#190). Modelled kinds (catalog/document/register) use the
     * per-entity read grant. A {@code comment} event is scoped to the commented record — a catalog or
     * document named {@code entityName} (see {@code CommentController}) — so it is authorized by that
     * record's read grant. Any other event type is delivered to nobody: fail closed, so a new event
     * kind can't leak before this filter is taught to authorize it. (The {@code presence} sentinel
     * is authorized by the publisher, which maps the record kind itself.)
     */
    public boolean canReceiveEvent(Set<String> roles, String entityType, String entityName) {
        // A wildcard ("*") change event names no specific entity and carries no row data — it is a
        // "something of this type changed, refetch" nudge. Document posting emits
        // ("changed","register","*") so any open register surface refetches; the rows themselves are
        // always re-read through the access-checked feed, so the bare nudge is safe for any
        // authenticated subscriber.
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
            default -> false;
        };
    }

    // ---------------------------------------------------------------- caller identity

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
     * authorities for the in-flight request. See issue #54.
     */
    public Set<String> roles(Principal principal) {
        Set<String> roles = authoritiesOf(principal);
        if (roles.isEmpty()) {
            roles = authoritiesOf(currentAuthentication());
        }
        return withDeclaredRoles(roles);
    }

    /**
     * {@code held} plus the declared roles it maps to through {@code RoleGrants.Spec#fromIdp}, so an
     * identity-provider role {@code erp-admins} mapped to {@code IT} also satisfies every role list
     * that names {@code IT} — command {@code .roles(...)}, task assignments, record-policy
     * {@code appliesTo}/{@code exemptRoles}, CRM workspaces and the UI's {@code roles} gates.
     */
    private Set<String> withDeclaredRoles(Set<String> held) {
        if (held.isEmpty() || grants.isOpen()) return held;
        Set<String> out = new LinkedHashSet<>(held);
        for (String role : grants.resolve(held).roles()) out.add(AccessSubject.normalizeRole(role));
        return out;
    }

    /**
     * The caller's login: the injected principal's name, else the name of the request's
     * {@code Authentication}, else {@code null} (anonymous).
     */
    public String username(Principal principal) {
        if (principal != null && principal.getName() != null) {
            return principal.getName();
        }
        Object auth = currentAuthentication();
        if (auth == null) return null;
        try {
            Object authenticated = invokePublic(auth, "isAuthenticated");
            if (Boolean.FALSE.equals(authenticated)) return null;
            if (auth.getClass().getName().endsWith("AnonymousAuthenticationToken")) return null;
            Object name = invokePublic(auth, "getName");
            return name instanceof String s ? s : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- internals

    /** The entity check for {@code subject}: trusted code passes, an anonymous user may do nothing. */
    private boolean can(AccessSubject subject, String type, String name, Class<?> javaClass, EntityRight right) {
        if (subject.trusted()) return true;
        if (anonymous(subject)) return false;
        return can(subject.roles(), type, name, javaClass, right);
    }

    /** A user subject resolved for a request without a signed-in user. */
    private static boolean anonymous(AccessSubject subject) {
        return subject instanceof AccessSubject.User user && (user.username() == null || user.username().isBlank());
    }

    /** The entity check for the caller of the current request; an anonymous caller may do nothing. */
    private boolean can(Principal principal, String type, String name, Class<?> javaClass, EntityRight right) {
        if (!signedIn(principal)) return false;
        return can(roles(principal), type, name, javaClass, right);
    }

    private boolean can(Set<String> roles, String type, String name, Class<?> javaClass, EntityRight right) {
        boolean write = right != EntityRight.READ;
        String normalized = normalizeName(name);
        for (EntityAccessPolicy policy : policies.get()) {
            if (!policy.allows(roles, type, normalized, write)) return false;
        }
        return grants.resolve(roles).can(javaClass, right);
    }

    /**
     * Whether a user is signed in for this request: a named principal (not Spring's anonymous token),
     * or an authenticated token in the {@code SecurityContext}.
     */
    private boolean signedIn(Principal principal) {
        if (principal != null) {
            return principal.getName() != null
                    && !principal.getClass().getName().endsWith("AnonymousAuthenticationToken");
        }
        return username(null) != null;
    }

    /** The Java class of the entity of {@code type} named {@code name} (route segment or display name), or null. */
    private Class<?> entityClass(String type, String name) {
        String normalized = normalizeName(name);
        return switch (type == null ? "" : type) {
            case "catalog" -> registry.allCatalogs().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst().<Class<?>>map(CatalogDescriptor::javaClass).orElse(null);
            case "document" -> registry.allDocuments().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst().<Class<?>>map(DocumentDescriptor::javaClass).orElse(null);
            case "register" -> registry.allRegisters().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst().<Class<?>>map(AccumulationRegisterDescriptor::javaClass).orElse(null);
            case "information register" -> registry.allInformationRegisters().stream()
                    .filter(d -> normalizeName(d.logicalName()).equals(normalized))
                    .findFirst().<Class<?>>map(InformationRegisterDescriptor::javaClass).orElse(null);
            default -> null;
        };
    }

    /**
     * Startup check of the grants against the model: every granted class is a registered catalog,
     * document or register, and post / unpost are granted on documents only.
     */
    static void validate(MetadataRegistry registry, RoleGrants grants) {
        if (grants.isOpen()) return;
        Set<Class<?>> entities = new LinkedHashSet<>();
        Set<Class<?>> documents = new LinkedHashSet<>();
        registry.allCatalogs().forEach(d -> entities.add(d.javaClass()));
        registry.allDocuments().forEach(d -> {
            entities.add(d.javaClass());
            documents.add(d.javaClass());
        });
        registry.allRegisters().forEach(d -> entities.add(d.javaClass()));
        registry.allInformationRegisters().forEach(d -> entities.add(d.javaClass()));

        List<String> problems = new ArrayList<>();
        for (Class<?> type : grants.entities()) {
            if (!entities.contains(type)) {
                problems.add(type.getName() + " is granted but is not a registered catalog, document or register");
            }
        }
        for (EntityRight right : List.of(EntityRight.POST, EntityRight.UNPOST)) {
            for (Class<?> type : grants.entities(right)) {
                if (entities.contains(type) && !documents.contains(type)) {
                    problems.add(right.name().toLowerCase(Locale.ROOT) + "(" + type.getName()
                            + ") is granted, but only documents can be posted or unposted");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid RoleGrants:\n  - " + String.join("\n  - ", problems));
        }
    }

    /** Strip spaces/underscores and lower-case, matching the generic controllers' {name} lookup. */
    private static String normalizeName(String name) {
        return name == null ? "" : name.replace(" ", "").replace("_", "").toLowerCase();
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
                        String normalized = AccessSubject.normalizeRole(role);
                        if (!normalized.isEmpty()) roles.add(normalized);
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

    private ServiceException forbidden(String type, String name) {
        return new ServiceException(ServiceException.Status.FORBIDDEN,
                "Current user is not allowed to access " + type + ": " + name);
    }

    private ServiceException forbiddenOperation(String operation, String name) {
        return new ServiceException(ServiceException.Status.FORBIDDEN,
                "Current user is not allowed to " + operation + " document: " + name);
    }
}
