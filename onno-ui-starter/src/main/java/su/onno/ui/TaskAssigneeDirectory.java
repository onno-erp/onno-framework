package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Resolves task assignees from the identity catalog configured by
 * {@link UiLayout#identity()}.
 */
public final class TaskAssigneeDirectory {

    private final MetadataRegistry registry;
    private final CatalogQueryService catalogs;
    private final UiAccessService access;
    private final UiLayout layout;

    public TaskAssigneeDirectory(
            MetadataRegistry registry,
            CatalogQueryService catalogs,
            UiAccessService access,
            UiLayout layout
    ) {
        this.registry = registry;
        this.catalogs = catalogs;
        this.access = access;
        this.layout = layout;
    }

    public List<AssigneeOption> search(String query, Principal principal) {
        UiIdentityLink link = layout.identity();
        if (link == null) {
            return List.of();
        }
        CatalogDescriptor descriptor = registry.allCatalogs().stream()
                .filter(catalog -> catalog.javaClass().equals(link.javaClass()))
                .findFirst().orElse(null);
        if (descriptor == null || !access.canRead(principal, descriptor)) {
            return List.of();
        }
        AttributeDescriptor login = descriptor.attributes().stream()
                .filter(attribute -> attribute.fieldName().equals(link.loginField()))
                .findFirst().orElse(null);
        if (login == null) {
            return List.of();
        }
        return catalogs.search(descriptor, query == null ? "" : query.trim(), 20).stream()
                .map(row -> option(row, login.columnName()))
                .filter(option -> option.username() != null && !option.username().isBlank())
                .toList();
    }

    private static AssigneeOption option(Map<String, Object> row, String loginColumn) {
        String username = text(row.get(loginColumn));
        String display = text(row.get("_description"));
        String id = text(row.get("_id"));
        return new AssigneeOption(username, display == null ? username : display, id);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    public record AssigneeOption(String username, String display, String recordId) {
    }
}
