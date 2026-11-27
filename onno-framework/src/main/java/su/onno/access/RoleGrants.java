package su.onno.access;

import su.onno.model.DocumentObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every role the application defines and what each may do: the one place access is declared.
 * Declare it as a bean:
 *
 * <pre>{@code
 * enum AppRole implements RoleKey { MANAGER, ACCOUNTANT, IT }
 *
 * @Bean
 * RoleGrants roles() {
 *     return RoleGrants.of(AppRole.class)
 *             .role(AppRole.MANAGER, r -> r
 *                     .read(Customer.class, Book.class)
 *                     .post(Order.class))                       // post implies write implies read
 *             .role(AppRole.ACCOUNTANT, r -> r
 *                     .readAll()
 *                     .unpost(Invoice.class)
 *                     .permit(CorePermissions.MANAGE_PERIOD_CLOSE))
 *             .role(AppRole.IT, r -> r
 *                     .permit(AuthPermissions.MANAGE_USERS, CorePermissions.EDIT_SETTINGS)
 *                     .fromIdp("erp-admins"));                  // also matched by this IdP role
 * }
 * }</pre>
 *
 * <p>Access is deny by default and nothing bypasses it: a user may do exactly what the roles they
 * hold grant, merged. There is no superuser role. Trusted code acting as
 * {@link AccessSubject#system()} is not a user and is not checked.
 *
 * <p>An application that declares no grants runs {@link #open()}: every signed-in user may do
 * everything. Declaring grants switches the whole application to deny by default.
 *
 * <p>Instances are immutable; {@link #role} returns a new instance.
 */
public final class RoleGrants {

    private static final RoleGrants OPEN = new RoleGrants(null, Map.of());

    private final Class<? extends RoleKey> roleType;
    private final Map<String, RoleGrant> roles;

    private RoleGrants(Class<? extends RoleKey> roleType, Map<String, RoleGrant> roles) {
        this.roleType = roleType;
        this.roles = roles;
    }

    /** The grants of an application with no roles: every signed-in user may do everything. */
    public static RoleGrants open() {
        return OPEN;
    }

    /** Start declaring grants for the roles of {@code roleType}, an enum implementing {@link RoleKey}. */
    public static <R extends Enum<R> & RoleKey> RoleGrants of(Class<R> roleType) {
        Objects.requireNonNull(roleType, "roleType");
        Map<String, RoleGrant> empty = new LinkedHashMap<>();
        for (R role : roleType.getEnumConstants()) {
            RoleGrant previous = empty.putIfAbsent(normalize(role.name()), RoleGrant.empty(role.name()));
            if (previous != null) {
                throw new IllegalArgumentException("Roles " + previous.name() + " and " + role.name() + " of "
                        + roleType.getName() + " are the same role: both normalize to " + normalize(role.name())
                        + ". Remove one of them.");
            }
        }
        return new RoleGrants(roleType, Collections.unmodifiableMap(empty));
    }

    /**
     * What {@code role} may do. Each role is declared at most once; a role of the enum that is never
     * declared exists (accounts may hold it) but grants nothing.
     */
    public <R extends Enum<R> & RoleKey> RoleGrants role(R role, Consumer<Spec> spec) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(spec, "spec");
        if (roleType == null || !roleType.equals(role.getDeclaringClass())) {
            throw new IllegalArgumentException("Role " + role + " is not a constant of " + roleType);
        }
        String key = normalize(role.name());
        if (roles.get(key).declared()) {
            throw new IllegalStateException("Role " + role.name() + " is declared twice");
        }
        Spec s = new Spec();
        spec.accept(s);
        Map<String, RoleGrant> next = new LinkedHashMap<>(roles);
        next.put(key, s.build(role.name()));
        RoleGrants result = new RoleGrants(roleType, Collections.unmodifiableMap(next));
        result.checkAliases();
        return result;
    }

    /** Whether the application declared no grants (everyone may do everything). */
    public boolean isOpen() {
        return roleType == null;
    }

    /** The declared role names in declaration order (empty when {@link #isOpen()}). */
    public List<String> roleNames() {
        return roles.values().stream().map(RoleGrant::name).toList();
    }

    /** Every entity class some role names, for startup validation against the model. */
    public Set<Class<?>> entities() {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (RoleGrant g : roles.values()) out.addAll(g.entities().keySet());
        return Collections.unmodifiableSet(out);
    }

    /**
     * Every entity class some role names with exactly {@code right} (not one it merely implies),
     * for startup validation — e.g. that {@code post(...)} names only documents.
     */
    public Set<Class<?>> entities(EntityRight right) {
        Objects.requireNonNull(right, "right");
        Set<Class<?>> out = new LinkedHashSet<>();
        for (RoleGrant g : roles.values()) {
            g.entities().forEach((type, rights) -> {
                if (rights.contains(right)) out.add(type);
            });
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Whether {@code role} names a role of the application's {@link RoleKey} enum or an
     * identity-provider name mapped with {@link Spec#fromIdp} (compared normalized). Always true for
     * {@link #open()}: an application without grants has no role vocabulary to check against.
     */
    public boolean recognizes(String role) {
        if (isOpen()) return true;
        String name = normalize(role);
        return roles.containsKey(name) || roles.values().stream().anyMatch(g -> g.idpNames().contains(name));
    }

    /**
     * Startup check of a role list written as strings (a command's {@code .roles(...)}, a print
     * form's roles, a workspace's roles, a record policy's roles, an {@code @McpTool}'s roles):
     * throws when a name is neither a role of the application's {@link RoleKey} enum nor a
     * {@link Spec#fromIdp} name, so a typo such as {@code "MANGER"} fails startup instead of
     * silently denying everyone. Does nothing for {@link #open()}.
     *
     * @param where what declares the list, for the message (e.g. {@code "command Orders#cancel .roles(...)"})
     */
    public void requireRecognized(String where, Collection<String> roleNames) {
        if (isOpen() || roleNames == null) return;
        List<String> unknown = roleNames.stream()
                .filter(r -> !recognizes(r))
                .map(r -> normalize(r).isEmpty() ? "<blank>" : normalize(r))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Unknown role " + unknown + " in " + where + ": a role must be a "
                    + "constant of " + roleType.getName() + " or a name mapped with fromIdp(...). Fix the name, "
                    + "add the role to the enum, or map it with fromIdp(...) in RoleGrants.");
        }
    }

    /** The declared roles that grant {@code permission}, by name, in declaration order. */
    public List<String> rolesGranting(Permission permission) {
        Objects.requireNonNull(permission, "permission");
        return roles.values().stream()
                .filter(g -> g.permissions().contains(permission))
                .map(RoleGrant::name)
                .toList();
    }

    /** Every permission some role is granted. */
    public Set<Permission> permissions() {
        Set<Permission> out = new LinkedHashSet<>();
        for (RoleGrant g : roles.values()) out.addAll(g.permissions());
        return Collections.unmodifiableSet(out);
    }

    /** What a subject may do: everything for {@link AccessSubject#system()}, else its roles' grants. */
    public EffectiveAccess resolve(AccessSubject subject) {
        Objects.requireNonNull(subject, "subject");
        if (subject instanceof AccessSubject.System || isOpen()) return EffectiveAccess.ALL;
        return resolve(subject.roles());
    }

    /**
     * What a user holding {@code heldRoles} may do. A held name matches a role by its name or any of
     * its {@link Spec#fromIdp} names, compared normalized; names matching no role grant nothing.
     */
    public EffectiveAccess resolve(Collection<String> heldRoles) {
        if (isOpen()) return EffectiveAccess.ALL;
        Set<String> held = new LinkedHashSet<>();
        if (heldRoles != null) for (String r : heldRoles) held.add(normalize(r));
        List<RoleGrant> matched = roles.values().stream()
                .filter(g -> held.contains(normalize(g.name())) || g.idpNames().stream().anyMatch(held::contains))
                .toList();
        return new EffectiveAccess(matched);
    }

    private void checkAliases() {
        Map<String, String> owner = new LinkedHashMap<>();
        for (RoleGrant g : roles.values()) owner.put(normalize(g.name()), g.name());
        for (RoleGrant g : roles.values()) {
            for (String alias : g.idpNames()) {
                String previous = owner.putIfAbsent(alias, g.name());
                if (previous != null && !previous.equals(g.name())) {
                    throw new IllegalStateException("Identity-provider role \"" + alias + "\" maps to both "
                            + previous + " and " + g.name());
                }
            }
        }
    }

    static String normalize(String role) {
        return AccessSubject.normalizeRole(role);
    }

    /** What one role may do, declared inside {@link RoleGrants#role}. */
    public static final class Spec {

        private final Map<Class<?>, EnumSet<EntityRight>> entities = new LinkedHashMap<>();
        private final EnumSet<EntityRight> everyEntity = EnumSet.noneOf(EntityRight.class);
        private final Set<Permission> permissions = new LinkedHashSet<>();
        private final Set<String> idpNames = new LinkedHashSet<>();

        private Spec() {
        }

        /** Read these entities. */
        public Spec read(Class<?>... entities) {
            return grant(EntityRight.READ, entities);
        }

        /** Read and write these entities. */
        public Spec write(Class<?>... entities) {
            return grant(EntityRight.WRITE, entities);
        }

        /** Post (and re-post) these documents, which includes writing and reading them. */
        public Spec post(Class<?>... documents) {
            return grant(EntityRight.POST, documents);
        }

        /** Unpost these documents, which includes writing and reading them. */
        public Spec unpost(Class<?>... documents) {
            return grant(EntityRight.UNPOST, documents);
        }

        /** Read every entity of the model. */
        public Spec readAll() {
            everyEntity.add(EntityRight.READ);
            return this;
        }

        /** Read and write every entity of the model. */
        public Spec writeAll() {
            everyEntity.add(EntityRight.WRITE);
            return this;
        }

        /**
         * Post every document of the model, which includes writing and reading them. Catalogs and
         * registers are not documents: this grants nothing on them.
         */
        public Spec postAll() {
            everyEntity.add(EntityRight.POST);
            return this;
        }

        /**
         * Unpost every document of the model, which includes writing and reading them. Catalogs and
         * registers are not documents: this grants nothing on them.
         */
        public Spec unpostAll() {
            everyEntity.add(EntityRight.UNPOST);
            return this;
        }

        /** Grant these capabilities. */
        public Spec permit(Permission... permissions) {
            for (Permission p : permissions) this.permissions.add(Objects.requireNonNull(p, "permission"));
            return this;
        }

        /**
         * Also match identity-provider (or account) roles with these names, in addition to the
         * role's own name — for an IdP whose role is called {@code erp-admins} rather than {@code IT}.
         */
        public Spec fromIdp(String... names) {
            for (String n : names) {
                String normalized = normalize(n);
                if (normalized.isEmpty()) throw new IllegalArgumentException("Blank identity-provider role name");
                idpNames.add(normalized);
            }
            return this;
        }

        private Spec grant(EntityRight right, Class<?>... types) {
            for (Class<?> type : types) {
                entities.computeIfAbsent(Objects.requireNonNull(type, "entity"), t -> EnumSet.noneOf(EntityRight.class))
                        .add(right);
            }
            return this;
        }

        private RoleGrant build(String name) {
            Map<Class<?>, Set<EntityRight>> copy = new LinkedHashMap<>();
            entities.forEach((type, rights) -> copy.put(type, Collections.unmodifiableSet(EnumSet.copyOf(rights))));
            return new RoleGrant(name, true, Collections.unmodifiableMap(copy),
                    everyEntity.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(everyEntity)),
                    Set.copyOf(permissions), Set.copyOf(idpNames));
        }
    }

    /** One role's resolved grants. */
    record RoleGrant(String name, boolean declared, Map<Class<?>, Set<EntityRight>> entities,
                     Set<EntityRight> everyEntity, Set<Permission> permissions, Set<String> idpNames) {

        static RoleGrant empty(String name) {
            return new RoleGrant(name, false, Map.of(), Set.of(), Set.of(), Set.of());
        }

        boolean can(Class<?> type, EntityRight right) {
            // postAll()/unpostAll() reach documents only: on a catalog or register they would
            // otherwise imply writing every one of them.
            boolean document = type != null && DocumentObject.class.isAssignableFrom(type);
            if (everyEntity.stream()
                    .filter(r -> document || (r != EntityRight.POST && r != EntityRight.UNPOST))
                    .anyMatch(r -> r.implies(right))) {
                return true;
            }
            Set<EntityRight> rights = entities.get(type);
            return rights != null && rights.stream().anyMatch(r -> r.implies(right));
        }
    }

    /** Debug form: the declared roles. */
    @Override
    public String toString() {
        return isOpen() ? "RoleGrants[open]" : "RoleGrants" + Arrays.toString(roleNames().toArray());
    }
}
