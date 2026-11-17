package su.onno.web.comments;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.identity.IdentityLink;
import su.onno.service.IdentityBinding;

import org.jdbi.v3.core.Jdbi;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves comment authors' avatar image URLs, live, from the identity catalog — the one the login
 * links to via {@link IdentityLink} (e.g. {@code Employee}) — using whichever of its attributes is
 * marked with an avatar/image field-hint widget ({@code .widget("avatar")}/{@code "image"}).
 * Resolution is best-effort and read-only: when there is no identity link, no
 * avatar-hinted attribute, or no stored value, it returns nothing and the comments panel falls back
 * to the author's initials. Resolving live (rather than snapshotting onto the comment row) keeps the
 * feed showing each author's current photo and avoids a column migration on {@code onno_comments}.
 */
public class CommentAuthorAvatars {

    private final Jdbi jdbi;
    /** The identity catalog's table + avatar column, or null when avatars can't be shown. */
    private final Source source;

    private record Source(String table, String avatarColumn) {}

    public CommentAuthorAvatars(IdentityBinding layout, MetadataRegistry registry, Jdbi jdbi) {
        this.jdbi = jdbi;
        this.source = resolveSource(layout, registry);
    }

    /** Author record id → avatar URL, for the given ids that have a non-blank one. Empty when unavailable. */
    public Map<String, String> avatarsFor(Collection<String> authorIds) {
        if (source == null || authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = authorIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(CommentAuthorAvatars::parseUuid)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Column/table names come from trusted descriptors; the ids are bound.
        String sql = "SELECT _id, " + source.avatarColumn() + " AS _avatar FROM " + source.table()
                + " WHERE _id IN (<ids>) AND _deletion_mark = false";
        return jdbi.withHandle(h -> {
            Map<String, String> out = new HashMap<>();
            h.createQuery(sql).bindList("ids", ids).mapToMap().forEach(row -> {
                Object id = row.get("_id");
                Object url = row.get("_avatar");
                if (id != null && url != null && !url.toString().isBlank()) {
                    out.put(id.toString(), url.toString());
                }
            });
            return out;
        });
    }

    /** The avatar URL for a single author id, or null. */
    public String avatarFor(String authorId) {
        return authorId == null ? null : avatarsFor(List.of(authorId)).get(authorId);
    }

    private static Source resolveSource(IdentityBinding layout, MetadataRegistry registry) {
        IdentityLink link = layout == null ? null : layout.identity();
        if (link == null) {
            return null;
        }
        CatalogDescriptor desc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass().equals(link.javaClass()))
                .findFirst().orElse(null);
        if (desc == null) {
            return null;
        }
        AttributeDescriptor avatar = su.onno.service.IdentityAvatars.attribute(desc).orElse(null);
        return avatar == null ? null : new Source(desc.tableName(), avatar.columnName());
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
