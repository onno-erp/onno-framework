package su.onno.importer;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import su.onno.archunit.OnnoRules;

import java.nio.file.Path;

/**
 * Architecture guardrails for CSV import. Each rule's failure message names the fix; the rules live
 * in {@link su.onno.archunit.OnnoRules}.
 */
class ImportArchitectureTest {

    /** This module's production classes (Gradle runs tests from the project directory). */
    private static final JavaClasses CLASSES = new ClassFileImporter().importPath(Path.of("build/classes/java/main"));

    @Test
    void postingRunsOutsideSpringTransactions() {
        OnnoRules.POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS.check(CLASSES);
    }

    @Test
    void plainListsDrainKeysetWindows() {
        OnnoRules.FULL_LISTS_DRAIN_KEYSET_WINDOWS.check(CLASSES);
    }

    @Test
    void domainModelIsFrameworkNeutral() {
        OnnoRules.DOMAIN_MODEL_IS_FRAMEWORK_NEUTRAL.check(CLASSES);
        OnnoRules.RECORDS_REFERENCE_RECORDS_BY_REF.check(CLASSES);
    }

    @Test
    void controllersUseScopedServices() {
        OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES.check(CLASSES);
    }

    @Test
    void collectionEndpointsReturnKeysetWindows() {
        OnnoRules.COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS.check(CLASSES);
    }
}
