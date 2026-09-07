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
}
