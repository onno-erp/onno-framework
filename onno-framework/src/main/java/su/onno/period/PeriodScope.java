package su.onno.period;

import java.util.Objects;

/**
 * What a period close applies to: every document ({@link #global()}), the documents and registers
 * of one bounded context ({@link #context(String)}), or the documents that move one accumulation
 * register ({@link #register(String)}).
 *
 * @param kind the scope kind
 * @param key  the context name, the register's logical name or the organization id; {@code "*"} for the global scope
 */
public record PeriodScope(Kind kind, String key) {

    /** The key stored for the global scope. */
    public static final String GLOBAL_KEY = "*";

    public enum Kind {
        GLOBAL,
        CONTEXT,
        REGISTER,
        ORGANIZATION
    }

    public PeriodScope {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.GLOBAL) {
            key = GLOBAL_KEY;
        } else if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A " + kind + " period scope needs a name");
        } else {
            key = key.trim();
        }
    }

    /** Every document, whatever its context or registers. */
    public static PeriodScope global() {
        return new PeriodScope(Kind.GLOBAL, GLOBAL_KEY);
    }

    /** Documents declared in {@code context}, and documents moving a register declared in it. */
    public static PeriodScope context(String context) {
        return new PeriodScope(Kind.CONTEXT, context);
    }

    /** Documents that move (or already moved) the accumulation register named {@code register}. */
    public static PeriodScope register(String register) {
        return new PeriodScope(Kind.REGISTER, register);
    }

    /**
     * Documents whose {@link PeriodCloseOrganization} field holds {@code organization} — a reference
     * (its id is used), a UUID, or any value compared by its string form.
     */
    public static PeriodScope organization(Object organization) {
        return new PeriodScope(Kind.ORGANIZATION, organizationKey(organization));
    }

    /** The string form an organization value is stored and compared as; {@code null} for none. */
    public static String organizationKey(Object value) {
        if (value == null) return null;
        if (value instanceof su.onno.types.Ref<?> ref) return ref.id() == null ? null : ref.id().toString();
        if (value instanceof java.util.Map<?, ?> map) return organizationKey(map.get("id"));
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Parses the {@code global} / {@code context:Name} / {@code register:Name} form produced by
     * {@link #toString()}.
     */
    public static PeriodScope parse(String text) {
        if (text == null || text.isBlank() || text.trim().equalsIgnoreCase("global")) {
            return global();
        }
        String value = text.trim();
        int colon = value.indexOf(':');
        if (colon > 0) {
            String kind = value.substring(0, colon).trim();
            String key = value.substring(colon + 1);
            if (kind.equalsIgnoreCase("context")) return context(key);
            if (kind.equalsIgnoreCase("register")) return register(key);
            if (kind.equalsIgnoreCase("organization")) return organization(key);
        }
        throw new IllegalArgumentException(
                "Unknown period scope '" + text + "'; use global, context:<name>, register:<name> or organization:<id>");
    }

    /** A phrase for messages: {@code all documents}, {@code context Finance}, {@code register Stock}. */
    public String describe() {
        return switch (kind) {
            case GLOBAL -> "all documents";
            case CONTEXT -> "context " + key;
            case REGISTER -> "register " + key;
            case ORGANIZATION -> "organization " + key;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case GLOBAL -> "global";
            case CONTEXT -> "context:" + key;
            case REGISTER -> "register:" + key;
            case ORGANIZATION -> "organization:" + key;
        };
    }
}
