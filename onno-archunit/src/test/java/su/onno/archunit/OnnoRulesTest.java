package su.onno.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;
import su.onno.archunit.fixtures.Fixtures;

import static org.assertj.core.api.Assertions.assertThat;

class OnnoRulesTest {

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    private static EvaluationResult evaluate(ArchRule rule, Class<?>... types) {
        return rule.evaluate(classes(types));
    }

    @Test
    void postingIsAllowedOutsideTransactionsAndWithSuspendedOnes() {
        assertThat(evaluate(OnnoRules.POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS, Fixtures.SavesThenPosts.class)
                .hasViolation()).isFalse();
    }

    @Test
    void postingInsideATransactionalMethodFails() {
        EvaluationResult result = evaluate(OnnoRules.POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS,
                Fixtures.PostsInsideTransaction.class);
        assertThat(result.getFailureReport().getDetails()).singleElement().asString()
                .contains("saveAndPost").contains("PostingService.post");
        assertThat(result.getFailureReport().toString()).contains("let it commit, then post");
    }

    @Test
    void postingFromALambdaInATransactionalClassFails() {
        assertThat(evaluate(OnnoRules.POSTING_RUNS_OUTSIDE_SPRING_TRANSACTIONS,
                Fixtures.TransactionalClassPostsInLambda.class).getFailureReport().getDetails())
                .anySatisfy(detail -> assertThat(detail).contains("PostingService.unpost"));
    }

    @Test
    void controllersMustNotUseTypedRepositoriesOrPostingService() {
        assertThat(evaluate(OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES, Fixtures.RepositoryController.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES, Fixtures.PostingController.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES, Fixtures.PlainController.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(OnnoRules.CONTROLLERS_USE_SCOPED_SERVICES, Fixtures.PagedController.class,
                Fixtures.SavesThenPosts.class).hasViolation()).isFalse();
    }

    @Test
    void domainClassesStayFrameworkNeutral() {
        assertThat(evaluate(OnnoRules.DOMAIN_MODEL_IS_FRAMEWORK_NEUTRAL, Fixtures.SpringAwareOrder.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(OnnoRules.DOMAIN_MODEL_IS_FRAMEWORK_NEUTRAL, Fixtures.Product.class,
                Fixtures.GoodLine.class, Fixtures.SavesThenPosts.class).hasViolation()).isFalse();
    }

    @Test
    void recordsReferenceRecordsByRef() {
        assertThat(evaluate(OnnoRules.RECORDS_REFERENCE_RECORDS_BY_REF, Fixtures.GoodLine.class,
                Fixtures.Product.class).hasViolation()).isFalse();
        assertThat(evaluate(OnnoRules.RECORDS_REFERENCE_RECORDS_BY_REF, Fixtures.EmbeddedProductLine.class)
                .hasViolation()).isTrue();
        assertThat(evaluate(OnnoRules.RECORDS_REFERENCE_RECORDS_BY_REF, Fixtures.EmbeddedProductList.class)
                .getFailureReport().getDetails()).hasSize(2);
    }

    @Test
    void plainListMethodsDrainKeysetWindows() {
        assertThat(evaluate(OnnoRules.FULL_LISTS_DRAIN_KEYSET_WINDOWS, Fixtures.Lists.class)
                .getFailureReport().getDetails()).singleElement().asString().contains("firstWindowOnly");
    }

    @Test
    void getEndpointsReturnKeysetWindows() {
        assertThat(evaluate(OnnoRules.COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS, Fixtures.PagedController.class)
                .hasViolation()).isFalse();
        assertThat(evaluate(OnnoRules.COLLECTION_ENDPOINTS_RETURN_KEYSET_WINDOWS, Fixtures.BareListController.class)
                .getFailureReport().getDetails()).hasSize(2);
    }

    @Test
    void allPassesOnCompliantClassesAndOnNothing() {
        assertThat(evaluate(OnnoRules.all(), Fixtures.Product.class, Fixtures.GoodLine.class,
                Fixtures.SavesThenPosts.class, Fixtures.PagedController.class).hasViolation()).isFalse();
        assertThat(evaluate(OnnoRules.all(), String.class).hasViolation()).isFalse();
    }
}
