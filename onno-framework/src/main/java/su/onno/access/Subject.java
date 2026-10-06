package su.onno.access;

import java.util.Objects;

/**
 * Placeholders for values taken from the {@link AccessSubject} at query time — the only
 * non-literal values a {@link RecordScope} may compare against. Everything binds as a parameter;
 * nothing a user controls is spliced into SQL.
 *
 * <pre>{@code
 * RecordScope.eq("owner", Subject.recordId())          // owner = the signed-in user's identity record
 * RecordScope.in("region", Subject.attribute("regions")) // a collection attribute
 * }</pre>
 *
 * <p>A placeholder the subject cannot supply (no identity record, attribute absent, or the
 * {@link AccessSubject.System} subject) makes its predicate <strong>false</strong> — a scope fails
 * closed, never open.
 */
public final class Subject {

    private Subject() {
    }

    /** The identity-linked record id of the signed-in user ({@code Layout.identity(...)}). */
    public static Value recordId() {
        return new Value(Source.RECORD_ID, null);
    }

    /** The signed-in user's login. */
    public static Value username() {
        return new Value(Source.USERNAME, null);
    }

    /** An attribute contributed by an {@link AccessSubjectContributor}. */
    public static Value attribute(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Subject attribute name must not be blank");
        }
        return new Value(Source.ATTRIBUTE, name);
    }

    /** Where a {@link Value} reads from. */
    public enum Source { RECORD_ID, USERNAME, ATTRIBUTE }

    /**
     * A subject-derived value placeholder.
     *
     * @param source which part of the subject to read
     * @param name   the attribute name for {@link Source#ATTRIBUTE}, otherwise {@code null}
     */
    public record Value(Source source, String name) {

        public Value {
            Objects.requireNonNull(source, "source");
        }

        /** The value for {@code subject}, or {@code null} when it cannot supply one (fail closed). */
        public Object resolve(AccessSubject subject) {
            if (!(subject instanceof AccessSubject.User user)) {
                return null;
            }
            return switch (source) {
                case RECORD_ID -> user.recordId();
                case USERNAME -> user.username();
                case ATTRIBUTE -> user.attribute(name);
            };
        }

        @Override
        public String toString() {
            return switch (source) {
                case RECORD_ID -> "subject.recordId";
                case USERNAME -> "subject.username";
                case ATTRIBUTE -> "subject.attribute(" + name + ")";
            };
        }
    }
}
