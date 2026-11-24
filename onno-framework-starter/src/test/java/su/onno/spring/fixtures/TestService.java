package su.onno.spring.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.AfterWriteHandler;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;

import lombok.Getter;
import lombok.Setter;

/**
 * A catalog with an {@code @Enumeration} attribute, used to prove enum attributes round-trip through
 * {@code repository.save(...)} / {@code findById(...)} (issue #26).
 */
@Catalog(name = "TestServices", autoNumber = false)
@Getter
@Setter
public class TestService extends CatalogObject implements BeforeDeleteHandler, BeforeWriteHandler,
        AfterWriteHandler, Validated {

    private static int deleteHookCalls;
    private static boolean assertDeleteSkipsWriteLifecycle;

    @Attribute(required = true, length = 120)
    private String name;

    @Attribute
    private TestServiceCategory category;

    @Override
    public void beforeWrite() {
        if (assertDeleteSkipsWriteLifecycle && isDeletionMark()) {
            throw new IllegalStateException("beforeWrite must not run during delete");
        }
    }

    @Override
    public void afterWrite() {
        if (assertDeleteSkipsWriteLifecycle && isDeletionMark()) {
            throw new IllegalStateException("afterWrite must not run during delete");
        }
    }

    @Override
    public void beforeDelete() {
        deleteHookCalls++;
    }

    @Override
    public java.util.List<BusinessRule> rules() {
        return java.util.List.of(new BusinessRule("not-deleting", "Rule must not run for delete",
                () -> !assertDeleteSkipsWriteLifecycle || !isDeletionMark()));
    }

    public static int deleteHookCalls() {
        return deleteHookCalls;
    }

    public static void resetDeleteHookCalls() {
        deleteHookCalls = 0;
    }

    public static void assertDeleteSkipsWriteLifecycle(boolean enabled) {
        assertDeleteSkipsWriteLifecycle = enabled;
    }
}
