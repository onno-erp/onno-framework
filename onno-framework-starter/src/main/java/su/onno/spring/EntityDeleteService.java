package su.onno.spring;

import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.transaction.annotation.Transactional;

/** Shared soft-delete command for repository and generic command entry points. */
public class EntityDeleteService {

    private final JdbcAggregateOperations operations;

    public EntityDeleteService(JdbcAggregateOperations operations) {
        this.operations = operations;
    }

    /**
     * Runs the delete hook and saves the deletion mark through Spring Data JDBC. Save callbacks
     * therefore keep versioning, outbox writes and entity-change events in the same transaction,
     * while the delete context suppresses the ordinary before-write and validation lifecycle.
     */
    @Transactional
    public void delete(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            if (catalog.isDeletionMark()) return;
            if (catalog.getId() == null || catalog.isNew()) {
                throw new IllegalArgumentException("Cannot delete an unsaved catalog aggregate");
            }
            markAndSave(aggregate, () -> catalog.setDeletionMark(true));
            return;
        }
        if (aggregate instanceof DocumentObject document) {
            if (document.isDeletionMark()) return;
            if (document.getId() == null || document.isNew()) {
                throw new IllegalArgumentException("Cannot delete an unsaved document aggregate");
            }
            if (document.isPosted()) {
                throw new IllegalStateException(
                        "Posted document " + document.getId() + " must be unposted before deletion");
            }
            markAndSave(aggregate, () -> document.setDeletionMark(true));
            return;
        }
        throw new IllegalArgumentException("Only catalog and document aggregates can be soft-deleted");
    }

    private void markAndSave(Object aggregate, Runnable mark) {
        if (aggregate instanceof BeforeDeleteHandler handler) handler.beforeDelete();
        mark.run();
        try (DeleteWriteContext ignored = DeleteWriteContext.enter(aggregate)) {
            operations.save(aggregate);
        }
    }
}
