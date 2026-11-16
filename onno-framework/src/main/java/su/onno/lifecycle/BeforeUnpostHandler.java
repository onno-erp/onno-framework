package su.onno.lifecycle;

/**
 * Domain hook that runs before a document is unposted — the counterpart of
 * {@link BeforePostHandler}. Throw (e.g. a {@code ValidationException}) to veto the unpost: nothing
 * is reversed and the document stays posted. It runs for every unpost, including the one a delete
 * of a posted document performs, but not for a re-post (which replaces movements without leaving
 * the posted state) nor for the internal reverse/replay of later documents during chronological
 * restoration.
 *
 * <p>Like the other domain hooks it has no Spring access; keep it a pure check over the
 * document's own state.
 */
public interface BeforeUnpostHandler {

    void beforeUnpost();
}
