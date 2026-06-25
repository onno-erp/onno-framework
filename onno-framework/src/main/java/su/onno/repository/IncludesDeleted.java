package su.onno.repository;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository finder as <em>intentionally</em> returning rows that are marked for deletion
 * (soft-deleted), opting it out of the deletion-aware finder check ({@code onno.repository.deletion-check}).
 *
 * <p>Catalogs and documents are soft-deleted: "delete" sets {@code deletionMark = true} and the row
 * stays in the table. Most business-logic finders must exclude those rows — use
 * {@link CatalogRepository#findAllActive()} / the {@code findActiveBy*} variants, or a derived
 * {@code ...AndDeletionMarkFalse} query. A few finders legitimately need the tombstones: resolving a
 * {@code Ref<T>} to a deleted target, or a restore/admin screen. Annotate those with
 * {@code @IncludesDeleted} to declare the intent and silence the check.</p>
 *
 * <pre>{@code
 * public interface OrderRepository extends DocumentRepository<Order> {
 *     // Upsert key for re-imports must see a previously deleted order, so admit tombstones:
 *     @IncludesDeleted
 *     Optional<Order> findByExternalNumber(String externalNumber);
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IncludesDeleted {
}
