package su.onno;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import su.onno.archunit.OnnoRules;

import java.nio.file.Path;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails for the core module. Failures name the fix; see
 * {@link su.onno.archunit.OnnoRules} for the shared rules.
 */
class CoreArchitectureTest {

    /** This module's production classes (Gradle runs tests from the project directory). */
    private static final JavaClasses CORE = new ClassFileImporter().importPath(Path.of("build/classes/java/main"));

    @Test
    void coreStaysIndependentOfTheSpringRuntime() {
        noClasses()
                .should().dependOnClassesThat(resideInAPackage("org.springframework..").and(not(
                        resideInAnyPackage(
                                "org.springframework.data.annotation..",
                                "org.springframework.data.domain..",
                                "org.springframework.data.repository.."))))
                .because("onno-framework is the transport-neutral core: it may use only Spring Data's "
                        + "mapping annotations and repository contracts. Spring wiring (beans, context, "
                        + "JDBC templates, transactions, web) belongs in onno-framework-starter "
                        + "(su.onno.spring / su.onno.service) or a web starter; expose a plain Java "
                        + "interface in core and implement it there")
                .allowEmptyShould(true)
                .check(CORE);
    }

    @Test
    void sharedOnnoRulesHold() {
        OnnoRules.POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS.check(CORE);
        OnnoRules.FULL_LISTS_DRAIN_KEYSET_WINDOWS.check(CORE);
        OnnoRules.RECORDS_REFERENCE_RECORDS_BY_REF.check(CORE);
    }
}
