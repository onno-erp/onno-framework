package su.onno.crm.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import su.onno.crm.domain.Conversation;

/**
 * An inbox workspace: which conversations it holds ({@code selection}), who may read and write them,
 * its folders and actions ({@code configure}) and the host's facets. Behaviour and scope only — how
 * the inbox presents (columns, sort, filters, labels) is the React {@code <CrmInbox>}'s props.
 * Selection never changes provider routing or persisted ownership. Facet keys must be unique.
 *
 * <p>Prefer the builder:
 * <pre>{@code
 * CrmInboxWorkspace.builder("support", "Customer support")
 *     .readRoles("MANAGER")
 *     .selection(conversation -> true)
 *     .facet(stageFacet)
 *     .build();
 * }</pre>
 */
public record CrmInboxWorkspace(String key, String label, Set<String> readRoles, Set<String> writeRoles,
                                Predicate<Conversation> selection,
                                UnaryOperator<CrmWorkspaceService.Config> configure,
                                List<Facet> facets) {
    public CrmInboxWorkspace {
        if (key == null || !key.matches("[a-z][a-z0-9_-]{0,39}") || label == null || label.isBlank() || label.length() > 80)
            throw new IllegalArgumentException("Inbox workspaces require a stable key and label");
        if (facets == null || facets.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Facets must not be null");
        facets = uniqueFacets(List.copyOf(facets));
        readRoles = Set.copyOf(readRoles); writeRoles = Set.copyOf(writeRoles);
        if (readRoles.isEmpty() || selection == null || configure == null) throw new IllegalArgumentException("Workspace access and selection must be explicit");
    }
    public CrmInboxWorkspace(String key, String label, Set<String> readRoles, Set<String> writeRoles,
            Predicate<Conversation> selection, UnaryOperator<CrmWorkspaceService.Config> configure) {
        this(key, label, readRoles, writeRoles, selection, configure, List.of());
    }
    public CrmInboxWorkspace(String key, String label, Set<String> roles, Predicate<Conversation> selection) {
        this(key, label, roles, roles, selection, UnaryOperator.identity());
    }

    public static Builder builder(String key, String label) {
        return new Builder(key, label);
    }

    /** Computes each conversation's facet values, keyed by conversation id. */
    @FunctionalInterface
    public interface FacetResolver {
        /**
         * {@code conversations} are already authorized for {@code principal}; the host must still
         * enforce the principal's access to any related records it reads. Calls receive bounded
         * batches (at most 500), so resolve each conversation independently of batch membership.
         */
        Map<UUID, List<String>> resolve(List<Conversation> conversations, Principal principal);
    }

    /**
     * Supplies a facet's options. Evaluated on every inbox read, so options may follow runtime data
     * (an editable tag library, configurable stages) without rebuilding the workspace bean.
     */
    @FunctionalInterface
    public interface FacetOptions {
        List<FacetOption> list();

        /** A fixed option list, copied once. */
        static FacetOptions of(List<FacetOption> options) {
            var fixed = List.copyOf(options);
            return () -> fixed;
        }
    }

    /**
     * Host-defined multi-select filter: the inbox feed accepts {@code in=<key>,<value>} for it and
     * offers its {@code label} and options as data. Resolve related data only for authorized conversations;
     * the host must enforce the principal's access to any related records it reads.
     *
     * <p>Options come from a {@link FacetOptions} source evaluated once per inbox read; a facet whose
     * source currently yields no options is omitted from the filter metadata and its row chips.
     *
     * <p>{@code showOnRows} shows each row's values as coloured chips. It is not free: the resolver
     * then runs in bounded batches over eligible conversations when used for selection, and for
     * visible page rows when decorating. Keep it to one or two batched queries per invocation.
     */
    public record Facet(String key, String label, FacetOptions optionSource, FacetResolver resolver, boolean showOnRows) {
        /** Row keys a facet must not shadow: conversation fields and every key the feed computes. */
        private static final Set<String> RESERVED_KEYS = reservedRowKeys();

        public Facet(String key, String label, List<FacetOption> options, FacetResolver resolver, boolean showOnRows) {
            this(key, label, FacetOptions.of(options), resolver, showOnRows);
        }
        public Facet(String key, String label, List<FacetOption> options, FacetResolver resolver) {
            this(key, label, options, resolver, false);
        }
        public Facet(String key, String label, FacetOptions options, FacetResolver resolver) {
            this(key, label, options, resolver, false);
        }
        public Facet {
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || label == null || label.isBlank())
                throw new IllegalArgumentException("Facet requires a key and label");
            if (RESERVED_KEYS.contains(key))
                throw new IllegalArgumentException("Facet key '" + key + "' would replace a conversation field or computed row key");
            Objects.requireNonNull(optionSource, "options");
            Objects.requireNonNull(resolver, "resolver");
        }
        /** Evaluates the option source now; call once per request and reuse the result. */
        public List<FacetOption> options() {
            return List.copyOf(Objects.requireNonNull(optionSource.list(), "Facet options source returned null"));
        }
        /**
         * Conversation fields (inherited catalog fields included), their {@code Display}/{@code Color}/{@code Ref}
         * companions, and the keys the inbox feed adds to each row.
         */
        private static Set<String> reservedRowKeys() {
            var names = new LinkedHashSet<>(List.of("id", "description", "customerAvatar", "rowBadges"));
            for (Class<?> type = Conversation.class; type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    names.add(field.getName());
                    for (String suffix : List.of("Display", "Color", "Ref")) names.add(field.getName() + suffix);
                }
            }
            return Set.copyOf(names);
        }
    }
    public record FacetOption(String value, String label, String color) {
        public FacetOption {
            if (value == null || value.isBlank() || label == null || label.isBlank()) throw new IllegalArgumentException("Facet option requires a value and label");
            if (color != null && !color.isBlank() && !color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Color must be #RRGGBB");
        }
    }

    public CrmInboxWorkspace facet(Facet facet) {
        var next = new ArrayList<>(facets);
        next.add(facet);
        return new CrmInboxWorkspace(key, label, readRoles, writeRoles, selection, configure, next);
    }

    /** Facet keys must not collide with each other. */
    private static List<Facet> uniqueFacets(List<Facet> facets) {
        var keys = new LinkedHashSet<String>();
        for (var facet : facets) {
            if (!keys.add(facet.key())) throw new IllegalArgumentException("Duplicate facet key: " + facet.key());
        }
        return facets;
    }

    /** Fluent construction. Write roles default to the read roles; configure and facets are optional. */
    public static final class Builder {
        private final String key;
        private final String label;
        private Set<String> readRoles = Set.of();
        private Set<String> writeRoles;
        private Predicate<Conversation> selection;
        private UnaryOperator<CrmWorkspaceService.Config> configure = UnaryOperator.identity();
        private final List<Facet> facets = new ArrayList<>();

        private Builder(String key, String label) {
            this.key = key;
            this.label = label;
        }
        public Builder readRoles(String... roles) { return readRoles(Set.of(roles)); }
        public Builder readRoles(Set<String> roles) { this.readRoles = roles; return this; }
        public Builder writeRoles(String... roles) { return writeRoles(Set.of(roles)); }
        public Builder writeRoles(Set<String> roles) { this.writeRoles = roles; return this; }
        public Builder selection(Predicate<Conversation> selection) { this.selection = selection; return this; }
        @SuppressWarnings("unused") // Public workspace builder API for consuming applications.
        public Builder configure(UnaryOperator<CrmWorkspaceService.Config> configure) { this.configure = configure; return this; }
        public Builder facet(Facet facet) { this.facets.add(Objects.requireNonNull(facet)); return this; }
        @SuppressWarnings("unused") // Public workspace builder API for consuming applications.
        public Builder facets(List<Facet> facets) { facets.forEach(this::facet); return this; }

        public CrmInboxWorkspace build() {
            var writers = writeRoles == null ? readRoles : writeRoles;
            return new CrmInboxWorkspace(key, label, readRoles, writers, selection, configure, facets);
        }
    }
}
