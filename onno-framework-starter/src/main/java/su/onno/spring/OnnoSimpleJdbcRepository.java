package su.onno.spring;

import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.repository.support.SimpleJdbcRepository;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JDBC repository base that implements the framework's deletion-mark contract for
 * catalogs and documents. Other aggregate types keep Spring Data's ordinary physical deletion.
 */
@Transactional(readOnly = true)
public class OnnoSimpleJdbcRepository<T, ID> extends SimpleJdbcRepository<T, ID> {

    private final JdbcAggregateOperations operations;

    public OnnoSimpleJdbcRepository(JdbcAggregateOperations operations,
                                    PersistentEntity<T, ?> entity,
                                    JdbcConverter converter) {
        super(operations, entity, converter);
        this.operations = operations;
    }

    @Override
    @Transactional
    public void deleteById(ID id) {
        findById(id).ifPresent(this::delete);
    }

    @Override
    @Transactional
    public void delete(T aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            beforeDelete(aggregate);
            catalog.setDeletionMark(true);
            operations.save(aggregate);
            return;
        }
        if (aggregate instanceof DocumentObject document) {
            if (document.isPosted()) {
                throw new IllegalStateException(
                        "Posted document " + document.getId() + " must be unposted before deletion");
            }
            beforeDelete(aggregate);
            document.setDeletionMark(true);
            operations.save(aggregate);
            return;
        }
        super.delete(aggregate);
    }

    @Override
    @Transactional
    public void deleteAllById(Iterable<? extends ID> ids) {
        ids.forEach(this::deleteById);
    }

    @Override
    @Transactional
    public void deleteAll(Iterable<? extends T> aggregates) {
        aggregates.forEach(this::delete);
    }

    @Override
    @Transactional
    public void deleteAll() {
        findAll().forEach(this::delete);
    }

    private static void beforeDelete(Object aggregate) {
        if (aggregate instanceof BeforeDeleteHandler handler) {
            handler.beforeDelete();
        }
    }
}
