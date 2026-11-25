package su.onno.model;

import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

/**
 * Base for {@code @Catalog(hierarchical = true)} entities. The parent is a reference to the
 * same catalog, stored in the existing {@code _parent UUID} column; {@code null} means a root.
 * Declare {@code class Category extends HierarchicalCatalogObject<Category>}.
 * Flat catalogs extend {@link CatalogObject} and have no folder or parent columns.
 *
 * @param <E> the concrete catalog entity type
 */
@Getter
@Setter
public abstract class HierarchicalCatalogObject<E extends HierarchicalCatalogObject<E>> extends CatalogObject {
    private boolean folder;
    private Ref<E> parent;
}
