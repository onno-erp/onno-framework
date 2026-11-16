package su.onno.history;

import java.util.function.Supplier;

/**
 * The thread-bound actor that record-history rows written on this thread are attributed to.
 *
 * <p>The generic command path binds the caller's {@code AccessSubject} for the duration of a
 * command, so everything it triggers on the same thread — the write itself, posting, repository
 * saves from lifecycle hooks — is attributed to that user. Trusted application code can do the same
 * for work it runs on a user's or an integration's behalf:
 *
 * <pre>{@code
 * HistoryActors.runAs(HistoryActor.named("guesty-sync"), () -> reservations.save(reservation));
 * }</pre>
 *
 * <p>With nothing bound, the recorder asks the {@link HistoryActorResolver} (the UI starter resolves
 * the request's authenticated user) and finally falls back to {@link HistoryActor#SYSTEM}.
 */
public final class HistoryActors {

    private static final ThreadLocal<HistoryActor> CURRENT = new ThreadLocal<>();

    private HistoryActors() {
    }

    /** The actor bound to this thread, or {@code null} when none is. */
    public static HistoryActor current() {
        return CURRENT.get();
    }

    /** Run {@code body} with {@code actor} bound, restoring the previous binding afterwards. */
    public static <T> T callAs(HistoryActor actor, Supplier<T> body) {
        HistoryActor previous = CURRENT.get();
        CURRENT.set(actor);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Run {@code body} with {@code actor} bound, restoring the previous binding afterwards. */
    public static void runAs(HistoryActor actor, Runnable body) {
        callAs(actor, () -> {
            body.run();
            return null;
        });
    }
}
