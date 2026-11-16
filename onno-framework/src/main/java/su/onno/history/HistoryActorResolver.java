package su.onno.history;

/**
 * Resolves the actor for a change recorded on a thread with no {@link HistoryActors} binding — a
 * typed {@code repository.save(...)} or {@code postingService.post(...)} called from application
 * code. The UI starter contributes one that returns the request's authenticated user; an
 * application may register its own bean to attribute changes differently.
 */
@FunctionalInterface
public interface HistoryActorResolver {

    /** The current actor, or {@code null} when unknown (recorded as {@link HistoryActor#SYSTEM}). */
    HistoryActor resolve();
}
