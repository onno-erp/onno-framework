package su.onno.crm.channels;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;
import su.onno.archunit.OnnoRules;

import java.nio.file.Path;

/**
 * Architecture guardrails for the CRM channel adapters. Each rule's failure message names the fix; the rules live
 * in {@link su.onno.archunit.OnnoRules}.
 */
class CrmChannelsArchitectureTest {

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

    /**
     * Controllers serve records through the record-scoped service layer. The frozen violations in
     * {@code src/test/resources/archunit_store} are CRM endpoints that authorize through the CRM
     * workspace model (CrmInboxWorkspaceService) before touching CRM repositories; new ones fail.
     */
    @Test
    void controllersUseScopedServices() {
        FreezingArchRule.freeze(OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES).check(CLASSES);
    }

    /**
     * New collection endpoints answer {@code {rows, nextCursor, hasMore}}. The frozen violations in
     * {@code src/test/resources/archunit_store} are the reviewed bounded selectors listed under
     * "Bounded exceptions" in docs/HEADLESS_READ_API.md; any new bare-list GET handler fails.
     */
    @Test
    void collectionEndpointsReturnKeysetWindows() {
        FreezingArchRule.freeze(OnnoRules.COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS).check(CLASSES);
    }
}
