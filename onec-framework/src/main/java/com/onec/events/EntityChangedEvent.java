package com.onec.events;

import java.util.UUID;

/**
 * Published whenever a catalog or document changes, regardless of which write path made the change —
 * the generic REST controllers (raw JDBI) and {@code repository.save(...)} (Spring Data JDBC) both
 * emit it, so server-side listeners see <em>every</em> change, not just back-office edits (issues
 * #28, #29).
 *
 * <p>Consume it with an ordinary Spring {@code @EventListener}; the built-in SSE bridge
 * (browser live updates) is just one such listener. Cache/ISR revalidation, search indexing, and
 * outbox relays become trivial listeners:
 *
 * <pre>{@code
 * @EventListener
 * void onChange(EntityChangedEvent event) {
 *     if ("catalog".equals(event.entityType()) && "Properties".equals(event.entityName())) {
 *         revalidate("/properties/" + event.naturalKey());   // slug, not just UUID
 *     }
 * }
 * }</pre>
 *
 * @param changeType  what happened: {@code created}, {@code updated}, {@code deleted}, {@code posted},
 *                    {@code unposted}, or {@code changed} (a coarse "something changed" signal, e.g.
 *                    register movements after a post).
 * @param entityType  the kind of entity. The framework's modelled kinds are {@code catalog},
 *                    {@code document}, and {@code register}; the vocabulary is open, and other
 *                    modules emit their own (e.g. the UI's comment threads emit {@code comment}).
 *                    Listeners filter on it, so an unrecognised kind is simply ignored.
 * @param entityName  the entity's registered logical name (e.g. {@code Properties}), or {@code *}
 *                    for a non-specific signal.
 * @param id          the affected row's id, or {@code null} when not applicable.
 * @param naturalKey  the business key that maps the change to an addressable resource — a catalog's
 *                    code or a document's number (the "slug") — or {@code null} if unknown. Carried
 *                    so listeners can target a specific resource instead of invalidating everything.
 */
public record EntityChangedEvent(
        String changeType,
        String entityType,
        String entityName,
        UUID id,
        String naturalKey) {

    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String DELETED = "deleted";
    public static final String POSTED = "posted";
    public static final String UNPOSTED = "unposted";
    public static final String CHANGED = "changed";

    public static final String CATALOG = "catalog";
    public static final String DOCUMENT = "document";
    public static final String REGISTER = "register";
}
