package su.onno.repository;

import su.onno.model.CatalogObject;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface CatalogRepository<T extends CatalogObject> extends ListCrudRepository<T, UUID> {

    Optional<T> findByCode(String code);

    // --- Soft-delete-aware finders -------------------------------------------------------------
    // Catalogs are SOFT-deleted: "delete" sets CatalogObject.deletionMark = true and the row stays
    // in the table (the UI/REST read layer hides such rows by filtering _deletion_mark = false).
    //
    // The inherited findAll()/findById()/findByCode() deliberately STILL return deletion-marked
    // rows, because some callers need them: RefResolver must resolve a Ref<T> to a deleted target
    // (so an old document can still show "Customer X"), and admin/restore flows must reach them.
    //
    // Business logic — login/admission, posting, totals, validation, picker option lists — must NOT
    // count deletion-marked rows. Use the findAllActive()/findActiveBy* variants below (or filter
    // !isDeletionMark() yourself). Forgetting this is a recurring source of "a deleted record still
    // took effect" bugs, so the safe path is given a name here rather than left to each caller.

    List<T> findByDeletionMarkFalse();

    Optional<T> findByCodeAndDeletionMarkFalse(String code);

    Optional<T> findByIdAndDeletionMarkFalse(UUID id);

    /** Every catalog item that is not marked for deletion — the safe default for business logic. */
    default List<T> findAllActive() {
        return findByDeletionMarkFalse();
    }

    /** The item with this code, unless it is marked for deletion. */
    default Optional<T> findActiveByCode(String code) {
        return findByCodeAndDeletionMarkFalse(code);
    }

    /** The item with this id, unless it is marked for deletion. */
    default Optional<T> findActiveById(UUID id) {
        return findByIdAndDeletionMarkFalse(id);
    }
}
