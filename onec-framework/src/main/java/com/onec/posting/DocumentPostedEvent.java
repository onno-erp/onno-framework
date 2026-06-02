package com.onec.posting;

import com.onec.model.DocumentObject;

/**
 * Published <em>after</em> a document has been successfully posted and its posting transaction has
 * committed (movements written, balances checked, {@code _posted} flipped). Consumers receive it via
 * a Spring {@code @EventListener}/{@code @TransactionalEventListener}, e.g.
 *
 * <pre>{@code
 * @EventListener
 * void onPosted(DocumentPostedEvent event) {
 *     if (event.document() instanceof GoodsReceipt receipt) { ... integrate ... }
 * }
 * }</pre>
 *
 * <p>This is the Spring-native alternative to the domain {@link com.onec.lifecycle.AfterPostHandler}
 * (which has no Spring access) and to the Kafka outbox (which requires that starter). The listener
 * runs after the post commit, so any side-effects it performs are safely post-commit.
 */
public record DocumentPostedEvent(DocumentObject document) {

    /** The posted document's id, or {@code null} if it was never assigned. */
    public Object documentId() {
        return document == null ? null : document.getId();
    }
}
