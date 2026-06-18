package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;

import java.security.Principal;
import java.util.Map;

/**
 * Resolves the authenticated principal to a domain catalog record via the
 * configured {@link UiIdentityLink} (e.g. login -> Employee.email), so persona
 * UIs can greet and scope to "the current person". Falls back to the raw
 * username when no link is configured or no record matches.
 */
public class CurrentUserResolver {

    private final UiLayout layout;
    private final MetadataRegistry registry;
    private final Jdbi jdbi;

    public CurrentUserResolver(UiLayout layout, MetadataRegistry registry, Jdbi jdbi) {
        this.layout = layout;
        this.registry = registry;
        this.jdbi = jdbi;
    }

    public record CurrentUser(String username, String displayName, String recordId, String entityName) {}

    public CurrentUser resolve(Principal principal) {
        String username = principal == null ? null : principal.getName();
        if (username == null || username.isBlank()) {
            return new CurrentUser(null, "Guest", null, null);
        }

        UiIdentityLink link = layout.identity();
        if (link == null) {
            return new CurrentUser(username, username, null, null);
        }

        CatalogDescriptor desc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass().equals(link.javaClass()))
                .findFirst().orElse(null);
        if (desc == null) {
            return new CurrentUser(username, username, null, null);
        }

        AttributeDescriptor loginAttr = desc.attributes().stream()
                .filter(a -> a.fieldName().equals(link.loginField()))
                .findFirst().orElse(null);
        if (loginAttr == null) {
            return new CurrentUser(username, username, null, desc.logicalName());
        }

        // Column name comes from the descriptor (trusted); the login value is bound.
        String sql = "SELECT _id, _description FROM " + desc.tableName()
                + " WHERE " + loginAttr.columnName() + " = :login AND _deletion_mark = false";
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery(sql).bind("login", username).mapToMap().findOne().orElse(null));

        if (row == null) {
            return new CurrentUser(username, username, null, desc.logicalName());
        }
        Object id = row.get("_id");
        Object description = row.get("_description");
        String display = description != null && !description.toString().isBlank()
                ? description.toString() : username;
        return new CurrentUser(username, display, id == null ? null : id.toString(), desc.logicalName());
    }
}
