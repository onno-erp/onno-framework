package su.onno.spring;

import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;

/**
 * The one soft-delete command for catalogs and documents, shared by typed repository deletes and the
 * generic command path (REST, UI, import, MCP).
 *
 * <p>It runs {@link BeforeDeleteHandler#beforeDelete()} (throw to veto), sets the deletion mark and
 * saves the aggregate through Spring Data JDBC. The save callbacks therefore bump the optimistic
 * version, check period close, record history and write the delete outbox row and change event in the
 * caller's transaction, while {@code beforeWrite}, attribute validation, business rules and
 * {@code afterWrite} are skipped: a record that no longer passes today's rules can still be deleted,
 * and a recalculating {@code beforeWrite} cannot change the stored row on its way out.
 *
 * <p>A posted document is rejected; unpost it explicitly first. The stored row is re-checked under a
 * row lock during the save, so a concurrent post cannot slip in between. Call it inside a
 * transaction.
 */
public class EntityDeleteService {

    private final JdbcAggregateOperations operations;

    public EntityDeleteService(JdbcAggregateOperations operations) {
        this.operations = operations;
    }

    /**
     * Soft-delete {@code aggregate}. An aggregate that is already deletion-marked, or was never
     * saved, is left as is (Spring Data's delete contract ignores what is not stored).
     *
     * @return the saved aggregate
     * @throws PostedDocumentDeletionException when the document is posted
     * @throws IllegalArgumentException when the aggregate is neither a catalog nor a document
     */
    public <T> T delete(T aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            if (catalog.isDeletionMark() || catalog.isNew()) return aggregate;
            return markAndSave(aggregate, () -> catalog.setDeletionMark(true));
        }
        if (aggregate instanceof DocumentObject document) {
            if (document.isDeletionMark() || document.isNew()) return aggregate;
            if (document.isPosted()) throw postedDocument(document);
            return markAndSave(aggregate, () -> document.setDeletionMark(true));
        }
        throw new IllegalArgumentException("Only catalog and document aggregates are soft-deleted: "
                + (aggregate == null ? "null" : aggregate.getClass().getName()));
    }

    static PostedDocumentDeletionException postedDocument(DocumentObject document) {
        return new PostedDocumentDeletionException(document.getId());
    }

    private <T> T markAndSave(T aggregate, Runnable mark) {
        if (aggregate instanceof BeforeDeleteHandler handler) handler.beforeDelete();
        mark.run();
        try (DeleteWriteContext ignored = DeleteWriteContext.enter(aggregate)) {
            return operations.save(aggregate);
        }
    }
}
