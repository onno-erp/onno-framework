package su.onno.repository;

import su.onno.model.DocumentObject;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface DocumentRepository<T extends DocumentObject> extends ListCrudRepository<T, UUID> {

    Optional<T> findByNumber(String number);

    List<T> findByDateBetween(LocalDateTime from, LocalDateTime to);

    // --- Soft-delete-aware finders -------------------------------------------------------------
    // Documents are SOFT-deleted: "delete" sets DocumentObject.deletionMark = true and the row
    // stays in the table. The inherited findAll()/findById()/findByNumber()/findByDateBetween()
    // still return deletion-marked rows (RefResolver and admin/restore rely on it); business logic
    // must NOT count them — use the findAllActive()/findActiveBy* variants below (or filter
    // !isDeletionMark()). See CatalogRepository for the full rationale.

    List<T> findByDeletionMarkFalse();

    Optional<T> findByNumberAndDeletionMarkFalse(String number);

    Optional<T> findByIdAndDeletionMarkFalse(UUID id);

    List<T> findByDateBetweenAndDeletionMarkFalse(LocalDateTime from, LocalDateTime to);

    /** Every document that is not marked for deletion — the safe default for business logic. */
    default List<T> findAllActive() {
        return findByDeletionMarkFalse();
    }

    /** The document with this number, unless it is marked for deletion. */
    default Optional<T> findActiveByNumber(String number) {
        return findByNumberAndDeletionMarkFalse(number);
    }

    /** The document with this id, unless it is marked for deletion. */
    default Optional<T> findActiveById(UUID id) {
        return findByIdAndDeletionMarkFalse(id);
    }

    /** Documents in the date range that are not marked for deletion. */
    default List<T> findActiveByDateBetween(LocalDateTime from, LocalDateTime to) {
        return findByDateBetweenAndDeletionMarkFalse(from, to);
    }
}
