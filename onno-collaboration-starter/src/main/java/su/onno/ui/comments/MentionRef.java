package su.onno.ui.comments;

import java.util.UUID;

/**
 * A typed reference embedded in a comment body — the comment-thread analogue of {@code Ref<T>}. It
 * carries the same {@code (kind, name, id)} route triple the UI uses to address any record, where
 * {@code kind} is {@code "catalogs"} or {@code "documents"} and {@code name} is the URL-safe route
 * segment (snake_case logical name, e.g. {@code "sales_orders"}). Like every other ref in the
 * framework, only the identity is stored — display, avatar and read access are resolved live (see
 * {@link MentionResolver}) so renames and deletes stay correct automatically.
 *
 * <p>In a stored body a mention is a token of the form {@code @[Display](kind/name/id)}; see
 * {@link Mentions} for the parser/serializer. Two mentions are equal when their triples match,
 * regardless of the snapshot label that accompanied them in the text.
 */
public record MentionRef(String kind, String name, UUID id) {

    public MentionRef {
        if (!"catalogs".equals(kind) && !"documents".equals(kind)) {
            throw new IllegalArgumentException("Mention kind must be 'catalogs' or 'documents': " + kind);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Mention name is required");
        }
        if (id == null) {
            throw new IllegalArgumentException("Mention id is required");
        }
    }

    /** The {@link su.onno.ui.UiAccessService} entity type for this kind: {@code "catalog"}/{@code "document"}. */
    public String accessType() {
        return "catalogs".equals(kind) ? "catalog" : "document";
    }

    /** The {@code kind/name/id} route segment, the body of an {@code onno://} navigation url. */
    public String route() {
        return kind + "/" + name + "/" + id;
    }
}
