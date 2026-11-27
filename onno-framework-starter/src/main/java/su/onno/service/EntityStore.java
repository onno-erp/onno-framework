package su.onno.service;

import su.onno.spring.EntityDeleteService;
import su.onno.spring.PostedDocumentDeletionException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Loads and saves catalog and document aggregates for the generic command services through Spring
 * Data JDBC — the same persistence path as the typed repositories. Every save therefore runs the
 * framework's entity callbacks: id and number assignment, {@code onFilling}/{@code beforeWrite},
 * attribute and business-rule validation, secret encryption, period-close checks, record history,
 * {@code afterWrite}, domain events and the commit-deferred {@code EntityChangedEvent}. The command
 * services add only what a caller-facing write needs on top: read-only mode, entity and record
 * access, and request-body mapping.
 *
 * <p>{@link #inTransaction} opens a Spring transaction that JDBI joins (the framework's {@code Jdbi}
 * bean uses a {@code SpringConnectionFactory}), so a save and the record-scope checks around it
 * commit or roll back together.
 */
public class EntityStore {

    private final JdbcAggregateOperations aggregates;
    private final TransactionTemplate transactions;
    private final EntityDeleteService deletes;

    public EntityStore(JdbcAggregateOperations aggregates, PlatformTransactionManager transactionManager) {
        this.aggregates = aggregates;
        this.transactions = new TransactionTemplate(transactionManager);
        this.deletes = new EntityDeleteService(aggregates);
    }

    /** The stored aggregate with secrets decrypted, or {@code null} when no row has that id. */
    public <T> T find(Class<T> type, UUID id) {
        return aggregates.findById(id, type);
    }

    /**
     * Insert (when {@code isNew()}) or update the aggregate, running every save callback. A stale
     * {@code version} surfaces as {@link OptimisticLockingFailureException}.
     */
    public <T> T save(T aggregate) {
        try {
            return aggregates.save(aggregate);
        } catch (DbActionExecutionException failed) {
            if (failed.getCause() instanceof OptimisticLockingFailureException stale) {
                throw stale;
            }
            throw failed;
        }
    }

    /**
     * Soft-delete the stored aggregate through {@link EntityDeleteService}, the same command a typed
     * repository delete runs, in one transaction. A posted document is rejected with
     * {@link PostedDocumentDeletionException}; a stale {@code version} surfaces as
     * {@link OptimisticLockingFailureException}.
     */
    public void delete(Object aggregate) {
        inTransaction(() -> {
            try {
                deletes.delete(aggregate);
            } catch (DbActionExecutionException failed) {
                if (failed.getCause() instanceof OptimisticLockingFailureException stale) {
                    throw stale;
                }
                if (failed.getCause() instanceof PostedDocumentDeletionException posted) {
                    throw posted;
                }
                throw failed;
            }
        });
    }

    /** Run {@code work} in one transaction; JDBI calls made inside it join that transaction. */
    public <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    public void inTransaction(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }
}
