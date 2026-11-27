package su.onno.lifecycle;

/**
 * Computes or validates values for the ordinary save pipeline (including write-validation previews).
 * Posting, re-posting, posting previews and chronological restoration do not invoke this hook.
 * Save and commit derived document fields before posting.
 */
public interface BeforeWriteHandler {

    void beforeWrite();
}
