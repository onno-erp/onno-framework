package com.onec.posting;

import com.onec.model.DocumentObject;

/**
 * Published <em>after</em> a document has been unposted and its unpost transaction has committed
 * (register movements reversed/deactivated, {@code _posted} cleared). The symmetric counterpart to
 * {@link DocumentPostedEvent}; useful for compensating an integration that reacted to the post.
 */
public record DocumentUnpostedEvent(DocumentObject document) {

    /** The unposted document's id, or {@code null} if it was never assigned. */
    public Object documentId() {
        return document == null ? null : document.getId();
    }
}
