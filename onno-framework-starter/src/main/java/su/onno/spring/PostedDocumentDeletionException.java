package su.onno.spring;

import java.util.UUID;

/**
 * A soft delete of a posted document was rejected: unpost it explicitly first. Deleting never
 * unposts implicitly, so the unpost right and {@code BeforeUnpostHandler} always apply. The generic
 * command path answers {@code 409}.
 */
public class PostedDocumentDeletionException extends IllegalStateException {

    private final UUID documentId;

    public PostedDocumentDeletionException(UUID documentId) {
        super("Posted document " + documentId + " must be unposted before deletion");
        this.documentId = documentId;
    }

    public UUID documentId() {
        return documentId;
    }
}
