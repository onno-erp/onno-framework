package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;

import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the authenticated principal to a domain catalog record via the
 * configured {@link UiIdentityLink} (e.g. login -> Employee.email), so persona
 * UIs can greet and scope to "the current person". Falls back to the raw
 * username when no link is configured or no record matches.
 *
 * <p>When the identity catalog carries an avatar/image-hinted attribute
 * ({@code .widget("avatar")}/{@code "image"}, the same rule the
 * comments panel uses), the resolver also reads that column so the shell can
 * paint the signed-in user's photo. Best-effort: no link, no avatar-hinted
 * column, or no stored value leaves {@code avatarUrl} null and the shell falls
 * back to the name-only identity block.</p>
 */
public class CurrentUserResolver {

    /** Field-hint widgets that mark an attribute as an avatar/photo image URL. */
    private static final Set<String> AVATAR_WIDGETS = Set.of("avatar", "image");

    private final UiLayout layout;
    private final MetadataRegistry registry;
    private final FieldHintResolver fieldHints;
    private final Jdbi jdbi;

    public CurrentUserResolver(UiLayout layout, MetadataRegistry registry,
                               FieldHintResolver fieldHints, Jdbi jdbi) {
        this.layout = layout;
        this.registry = registry;
        this.fieldHints = fieldHints;
        this.jdbi = jdbi;
    }

    public record CurrentUser(String username, String displayName, String recordId,
                              String entityName, String avatarUrl) {}

    public CurrentUser resolve(Principal principal) {
        String username = principal == null ? null : principal.getName();
        if (username == null || username.isBlank()) {
            return new CurrentUser(null, "Guest", null, null, null);
        }

        UiIdentityLink link = layout.identity();
        if (link == null) {
            return new CurrentUser(username, username, null, null, null);
        }

        CatalogDescriptor desc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass().equals(link.javaClass()))
                .findFirst().orElse(null);
        if (desc == null) {
            return new CurrentUser(username, username, null, null, null);
        }

        AttributeDescriptor loginAttr = desc.attributes().stream()
                .filter(a -> a.fieldName().equals(link.loginField()))
                .findFirst().orElse(null);
        if (loginAttr == null) {
            return new CurrentUser(username, username, null, desc.logicalName(), null);
        }

        // The identity catalog's avatar-hinted column, if it declares one — read in the same
        // query so a linked user's photo comes back without a second round trip.
        String avatarColumn = avatarColumn(desc);

        // Column names come from the descriptor (trusted); the login value is bound.
        //
        // The comparison is case-INSENSITIVE. Logins are case-insensitive identifiers everywhere a
        // user types one, and an SSO principal arrives with whatever casing the provider reports —
        // Telegram, for instance, stamps the @username exactly as its owner set it. Matching those
        // with SQL `=` means a stored login that differs only in case silently resolves to nobody:
        // the person stays signed in, but loses their record id, and with it their display name,
        // their photo, comment authorship and presence identity. Lower-casing in Java rather than
        // via `lower(:login)` keeps the bound parameter a plain string, so PostgreSQL never has to
        // infer a type for it.
        //
        // Rows differing only in case are a data error, not a lookup error, so this orders by id and
        // takes the first rather than failing the request for everyone involved.
        String avatarSelect = avatarColumn == null ? "" : ", " + avatarColumn + " AS _avatar";
        String sql = "SELECT _id, _description" + avatarSelect + " FROM " + desc.tableName()
                + " WHERE lower(" + loginAttr.columnName() + ") = :login AND _deletion_mark = false"
                + " ORDER BY _id";
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery(sql).bind("login", username.toLowerCase(Locale.ROOT))
                        .mapToMap().findFirst().orElse(null));

        if (row == null) {
            return new CurrentUser(username, username, null, desc.logicalName(), null);
        }
        Object id = row.get("_id");
        Object description = row.get("_description");
        String display = description != null && !description.toString().isBlank()
                ? description.toString() : username;
        Object avatar = avatarColumn == null ? null : row.get("_avatar");
        String avatarUrl = avatar != null && !avatar.toString().isBlank() ? avatar.toString() : null;
        return new CurrentUser(username, display, id == null ? null : id.toString(),
                desc.logicalName(), avatarUrl);
    }

    /** The catalog's avatar/image-hinted column name, or null when it declares none. */
    private String avatarColumn(CatalogDescriptor desc) {
        Map<String, FieldHint> hints = fieldHints.forEntity(desc.javaClass());
        return desc.attributes().stream()
                .filter(a -> {
                    FieldHint hint = hints.get(a.fieldName());
                    return hint != null && hint.widget() != null
                            && AVATAR_WIDGETS.contains(hint.widget().trim().toLowerCase(Locale.ROOT));
                })
                .map(AttributeDescriptor::columnName)
                .findFirst().orElse(null);
    }
}
