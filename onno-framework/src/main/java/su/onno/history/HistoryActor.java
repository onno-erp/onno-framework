package su.onno.history;

import su.onno.access.AccessSubject;

import java.util.UUID;

/**
 * Who a recorded change is attributed to.
 *
 * @param name     the login (principal name), or a label for trusted code such as {@code "system"}
 *                 or an integration name
 * @param recordId the identity-linked catalog record id ({@code Layout.identity(...)}), or
 *                 {@code null} when the actor is not a signed-in user with an identity record
 */
public record HistoryActor(String name, UUID recordId) {

    /** The actor recorded for trusted framework or application code with no signed-in user. */
    public static final HistoryActor SYSTEM = new HistoryActor("system", null);

    public HistoryActor {
        if (name == null || name.isBlank()) {
            name = SYSTEM.name;
        }
    }

    /** A named actor without an identity record, e.g. an integration: {@code HistoryActor.named("guesty-sync")}. */
    public static HistoryActor named(String name) {
        return new HistoryActor(name, null);
    }

    /** The actor for an {@link AccessSubject}: the user's login and identity record, or {@link #SYSTEM}. */
    public static HistoryActor of(AccessSubject subject) {
        if (subject instanceof AccessSubject.User user) {
            return new HistoryActor(user.username() == null || user.username().isBlank()
                    ? "anonymous" : user.username(), user.recordId());
        }
        return SYSTEM;
    }
}
