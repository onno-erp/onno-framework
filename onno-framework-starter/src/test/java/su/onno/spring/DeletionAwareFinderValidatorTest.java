package su.onno.spring;

import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.repository.CatalogRepository;
import su.onno.repository.DocumentRepository;
import su.onno.repository.IncludesDeleted;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tests the detection logic of {@link DeletionAwareFinderValidator}: a consumer-declared finder
 * that returns entities must be deletion-scoped (name predicate / {@code @Query} filter) or opted out
 * with {@link IncludesDeleted}; inherited framework finders and non-entity methods are never flagged.
 */
class DeletionAwareFinderValidatorTest {

    static class Widget extends CatalogObject {
    }

    static class Shipment extends DocumentObject {
    }

    interface CleanCatalogRepo extends CatalogRepository<Widget> {
        Optional<Widget> findByColorAndDeletionMarkFalse(String color); // deletion-scoped → ok
    }

    interface LeakyCatalogRepo extends CatalogRepository<Widget> {
        Optional<Widget> findByExternalNumber(String externalNumber);   // entity, unscoped → violation
        List<Widget> findByColorAndDeletionMarkFalse(String color);     // scoped → ok
    }

    interface OptedOutRepo extends CatalogRepository<Widget> {
        @IncludesDeleted
        Optional<Widget> findByExternalNumber(String externalNumber);   // explicit opt-out → ok
    }

    interface NonFinderRepo extends CatalogRepository<Widget> {
        long countByColor(String color);        // not entity-returning → ignored
        boolean existsByColor(String color);    // ignored
        void deleteByColor(String color);       // ignored
    }

    interface LeakyDocumentRepo extends DocumentRepository<Shipment> {
        List<Shipment> findByCarrier(String carrier);                       // violation
        Optional<Shipment> findByTrackingAndDeletionMarkFalse(String code); // ok
    }

    @Test
    void flagsUnscopedEntityFinder() {
        assertThat(DeletionAwareFinderValidator.findViolations(LeakyCatalogRepo.class, Widget.class))
                .containsExactly("LeakyCatalogRepo.findByExternalNumber(...)");
    }

    @Test
    void cleanRepoHasNoViolations() {
        assertThat(DeletionAwareFinderValidator.findViolations(CleanCatalogRepo.class, Widget.class)).isEmpty();
    }

    @Test
    void includesDeletedOptsOut() {
        assertThat(DeletionAwareFinderValidator.findViolations(OptedOutRepo.class, Widget.class)).isEmpty();
    }

    @Test
    void ignoresNonEntityReturningMethods() {
        assertThat(DeletionAwareFinderValidator.findViolations(NonFinderRepo.class, Widget.class)).isEmpty();
    }

    @Test
    void flagsDocumentFinders() {
        assertThat(DeletionAwareFinderValidator.findViolations(LeakyDocumentRepo.class, Shipment.class))
                .containsExactly("LeakyDocumentRepo.findByCarrier(...)");
    }

    @Test
    void strictModeThrowsOnViolations() {
        DeletionAwareFinderValidator strict =
                new DeletionAwareFinderValidator(null, DeletionAwareFinderValidator.Mode.STRICT);
        assertThatThrownBy(() -> strict.report(List.of("LeakyCatalogRepo.findByExternalNumber(...)")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("findByExternalNumber")
                .hasMessageContaining("@su.onno.repository.IncludesDeleted");
    }

    @Test
    void warnModeDoesNotThrow() {
        DeletionAwareFinderValidator warn =
                new DeletionAwareFinderValidator(null, DeletionAwareFinderValidator.Mode.WARN);
        warn.report(List.of("LeakyCatalogRepo.findByExternalNumber(...)")); // logs, does not throw
        warn.report(List.of()); // nothing to report
    }

    @Test
    void modeParsing() {
        assertThat(DeletionAwareFinderValidator.Mode.fromString("strict"))
                .isEqualTo(DeletionAwareFinderValidator.Mode.STRICT);
        assertThat(DeletionAwareFinderValidator.Mode.fromString("OFF"))
                .isEqualTo(DeletionAwareFinderValidator.Mode.OFF);
        assertThat(DeletionAwareFinderValidator.Mode.fromString(null))
                .isEqualTo(DeletionAwareFinderValidator.Mode.WARN);
        assertThatThrownBy(() -> DeletionAwareFinderValidator.Mode.fromString("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
