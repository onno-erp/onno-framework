package su.onno.lifecycle;

/**
 * Prepares or vetoes posting, re-posting, posting previews and chronological restoration.
 * Changes to the object are available to posting rules and movement calculation, but are not
 * saved to its document attributes or tabular sections. Keep this hook safe to replay.
 */
public interface BeforePostHandler {

    void beforePost();
}
