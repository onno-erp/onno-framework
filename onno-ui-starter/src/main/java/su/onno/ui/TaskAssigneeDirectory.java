package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.comments.CommentAuthorAvatars;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import su.onno.process.ProcessActorId;
import su.onno.process.ProcessIdentity;

/**
 * Resolves task assignees from the identity catalog configured by
 * {@link UiLayout#identity()}.
 */
public final class TaskAssigneeDirectory {

    private final MetadataRegistry registry;
    private final CatalogQueryService catalogs;
    private final UiAccessService access;
    private final UiLayout layout;
    private final CommentAuthorAvatars avatars;

    public TaskAssigneeDirectory(
            MetadataRegistry registry,
            CatalogQueryService catalogs,
            UiAccessService access,
            UiLayout layout,
            CommentAuthorAvatars avatars
    ) {
        this.registry = registry;
        this.catalogs = catalogs;
        this.access = access;
        this.layout = layout;
        this.avatars = avatars;
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
        List<Map<String, Object>> rows =
                catalogs.search(descriptor, query == null ? "" : query.trim(), 20);
        Map<String, String> avatarUrls = avatars == null
                ? Map.of()
                : avatars.avatarsFor(rows.stream().map(row -> text(row.get("_id"))).toList());
        return rows.stream()
                .map(row -> option(row, login.columnName(), avatarUrls.get(text(row.get("_id")))))
                .filter(option -> option.username() != null && !option.username().isBlank())
                .toList();
    }

    public ProcessIdentity require(String actorId, Principal principal) {
        Directory directory = directory(principal);
        UUID id;
        try {
            id = UUID.fromString(actorId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("targetActorId must be an identity record UUID");
        }
        AssigneeOption option = option(
                catalogs.get(directory.descriptor(), id), directory.loginColumn(), null);
        if (option.username() == null || option.username().isBlank()) {
            throw new IllegalArgumentException("Selected identity has no configured login");
        }
        return new ProcessIdentity(
                ProcessActorId.of(option.actorId()), option.username(), option.display());
    }

    private static AssigneeOption option(
            Map<String, Object> row,
            String loginColumn,
            String avatarUrl
    ) {
        String username = text(row.get(loginColumn));
        String display = text(row.get("_description"));
        String id = text(row.get("_id"));
        return new AssigneeOption(id, username, display == null ? username : display, avatarUrl);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private Directory directory(Principal principal) {
        UiIdentityLink link = layout.identity();
        if (link == null) {
            throw new IllegalStateException("Layout.identity(...) is required for task delegation");
        }
        CatalogDescriptor descriptor = registry.allCatalogs().stream()
                .filter(catalog -> catalog.javaClass().equals(link.javaClass()))
                .findFirst().orElseThrow(() ->
                        new IllegalStateException("Identity catalog is not registered"));
        if (!access.canRead(principal, descriptor)) {
            throw new SecurityException("Current user cannot read the identity catalog");
        }
        AttributeDescriptor login = descriptor.attributes().stream()
                .filter(attribute -> attribute.fieldName().equals(link.loginField()))
                .findFirst().orElseThrow(() ->
                        new IllegalStateException("Identity login field is not registered"));
        return new Directory(descriptor, login.columnName());
    }

    private record Directory(CatalogDescriptor descriptor, String loginColumn) {
    }

    public record AssigneeOption(
            String actorId,
            String username,
            String display,
            String avatarUrl
    ) {
    }
}
