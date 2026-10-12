package su.onno.ui.tags;

import java.util.List;
import java.util.UUID;

/** Binds a record scope to an ordinary application-owned tag catalog. */
public interface TagCatalog {
    String scope();
    /** Logical catalog name used for live invalidation. */
    String catalogName();
    List<TagService.Tag> list();
    /** Import a legacy definition once, retaining its ID; never resurrect an existing deleted row. */
    TagService.Tag importTag(UUID id, String name, String color);
    /**
     * Create a definition from the inline tag picker. The caller has already checked write access to
     * {@link #catalogName()}. The default delegates to {@link #importTag} with a fresh id; override
     * to apply catalog-specific defaults.
     */
    default TagService.Tag create(String name, String color) {
        return importTag(UUID.randomUUID(), name, color);
    }
}
