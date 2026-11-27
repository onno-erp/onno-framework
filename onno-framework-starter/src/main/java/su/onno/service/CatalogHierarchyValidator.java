package su.onno.service;

import su.onno.metadata.CatalogDescriptor;
import su.onno.model.CatalogObject;
import su.onno.validation.ValidationErrors;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.HashSet;
import java.util.UUID;

/** Checks the persisted parent chain after the entity's filling and before-write hooks. */
public final class CatalogHierarchyValidator {

    private final Jdbi jdbi;

    public CatalogHierarchyValidator(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Read-only form preview; the real save repeats the check while locking the chain. */
    public void collect(CatalogDescriptor descriptor, CatalogObject entity, ValidationErrors errors) {
        collect(descriptor, entity, errors, false, false);
    }

    /**
     * Runs inside the repository's Spring transaction. Lock the written row and its ancestors so
     * concurrent moves cannot both validate against the old tree and then create a cycle.
     */
    public void collectForWrite(CatalogDescriptor descriptor, CatalogObject entity, ValidationErrors errors) {
        collect(descriptor, entity, errors, true, false);
    }

    /**
     * The structural part of a soft delete, which otherwise skips validation: a folder with active
     * children cannot be deleted. The parent chain is not re-validated, so an item under an already
     * deleted folder can still be deleted. Runs inside the delete's transaction and locks the row.
     */
    public void collectForDelete(CatalogDescriptor descriptor, CatalogObject entity, ValidationErrors errors) {
        collect(descriptor, entity, errors, true, true);
    }

    private void collect(CatalogDescriptor descriptor, CatalogObject entity, ValidationErrors errors,
                         boolean lock, boolean deleting) {
        if (!descriptor.hierarchical()) return;
        jdbi.useHandle(handle -> {
            String table = descriptor.tableName();
            if (lock && entity.getId() != null) {
                handle.createQuery("SELECT _id FROM " + table + " WHERE _id = :id FOR UPDATE")
                        .bind("id", entity.getId()).mapTo(UUID.class).findOne();
            }
            // A folder's children must not lose their parent through a flag-only update.
            if ((!entity.isFolder() || entity.isDeletionMark()) && entity.getId() != null
                    && handle.createQuery("SELECT _id FROM " + table
                            + " WHERE _parent = :id AND _deletion_mark = false LIMIT 1")
                            .bind("id", entity.getId()).mapTo(UUID.class).findFirst().isPresent()) {
                errors.field("parent", "A folder with active children cannot be deleted or changed to an item");
            }
            if (deleting) return;
            var visited = new HashSet<UUID>();
            if (entity.getId() != null) visited.add(entity.getId());
            UUID parent = entity.getParent();
            while (parent != null) {
                if (!visited.add(parent)) {
                    errors.field("parent", "Parent would create a cycle in the catalog hierarchy");
                    return;
                }
                Node node = read(handle, table, parent, lock);
                if (node == null || node.deleted()) {
                    errors.field("parent", "Parent must be an existing folder that is not marked for deletion");
                    return;
                }
                if (!node.folder()) {
                    errors.field("parent", "Parent must be a folder");
                    return;
                }
                parent = node.parent();
            }
        });
    }

    private static Node read(Handle handle, String table, UUID id, boolean lock) {
        return handle.createQuery("SELECT _parent, _is_folder, _deletion_mark FROM " + table
                        + " WHERE _id = :id" + (lock ? " FOR UPDATE" : ""))
                .bind("id", id)
                .map((row, context) -> new Node(row.getObject("_parent", UUID.class),
                        row.getBoolean("_is_folder"), row.getBoolean("_deletion_mark")))
                .findOne().orElse(null);
    }

    private record Node(UUID parent, boolean folder, boolean deleted) { }
}
