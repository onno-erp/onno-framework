package su.onno.service;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.archunit.OnnoRules;

import java.nio.file.Path;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Architecture guardrails for the starter's service layer and Spring wiring. Failures name the fix;
 * see {@link su.onno.archunit.OnnoRules} for the shared rules.
 */
class ServiceLayerArchitectureTest {

    /** This module's production classes (Gradle runs tests from the project directory). */
    private static final JavaClasses STARTER = new ClassFileImporter().importPath(Path.of("build/classes/java/main"));

    /**
     * Public service methods that read no records: metadata lookups, request parsing and wiring.
     * Add a name only when the method touches no entity rows.
     */
    private static final Set<String> METADATA_ONLY = Set.of(
            // descriptor lookups
            "require", "forClass",
            // column/filter/schema metadata of a descriptor
            "sortableColumns", "whereSchema", "movementWhereSchema", "balanceWhereSchema",
            "balanceFilterColumns", "numericFilterColumns", "movementFilterColumns", "storageColumn",
            "dimensionFilters",
            // pure transformations of values the caller already holds
            "logical", "idList", "extract", "routeTarget",
            // collaborators and setter injection
            "scopes", "refResolver", "registry", "compiler", "policies", "setPeriodCloseService");

    @Test
    void serviceQueriesAndCommandsTakeAnAccessSubject() {
        methods()
                .that(arePublicServiceOperations())
                .should(takeAnAccessSubject())
                .because("every su.onno.service query/command method takes the caller's AccessSubject "
                        + "so RoleGrants and RecordAccessPolicy scopes apply: add an AccessSubject "
                        + "parameter (request code passes the resolved subject, trusted background code "
                        + "AccessSubject.system()). A method that reads no records belongs in "
                        + "METADATA_ONLY with that justification")
                .check(STARTER);
    }

    @Test
    void sharedOnnoRulesHold() {
        OnnoRules.POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS.check(STARTER);
        OnnoRules.FULL_LISTS_DRAIN_KEYSET_WINDOWS.check(STARTER);
    }

    private static DescribedPredicate<JavaMethod> arePublicServiceOperations() {
        return new DescribedPredicate<>("are public su.onno.service query/command operations") {
            @Override
            public boolean test(JavaMethod method) {
                String owner = method.getOwner().getSimpleName();
                return method.getOwner().getPackageName().equals("su.onno.service")
                        && (owner.endsWith("QueryService") || owner.endsWith("CommandService")
                        || owner.equals("RecordAccess") || owner.equals("RelationWriteService"))
                        && method.getModifiers().contains(JavaModifier.PUBLIC)
                        && !method.getModifiers().contains(JavaModifier.SYNTHETIC)
                        && !METADATA_ONLY.contains(method.getName());
            }
        };
    }

    private static ArchCondition<JavaMethod> takeAnAccessSubject() {
        return new ArchCondition<>("take an AccessSubject") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean takesSubject = method.getRawParameterTypes().stream()
                        .anyMatch(type -> type.isEquivalentTo(AccessSubject.class));
                if (!takesSubject) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " has no AccessSubject parameter"));
                }
            }
        };
    }
}
