package su.onno.spring.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.lifecycle.AfterWriteHandler;
import su.onno.lifecycle.BeforeDeleteHandler;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.HasBusinessRules;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * A catalog whose write lifecycle refuses a deletion-marked record, so a delete only succeeds when it
 * skips {@code beforeWrite}, validation and {@code afterWrite} (issue #558). Counts delete-hook calls.
 */
@Catalog(name = "DeleteGuardedThings", autoNumber = false)
@Getter
@Setter
public class TestDeleteGuardedCatalog extends CatalogObject
        implements BeforeDeleteHandler, BeforeWriteHandler, AfterWriteHandler, HasBusinessRules {

    public static int deleteHookCalls;

    @Attribute(length = 120)
    private String name;

    /** Recalculated by {@code beforeWrite}; a delete must not change it. */
    @Attribute
    private Integer writes;

    @Override
    public void beforeWrite() {
        if (isDeletionMark()) throw new IllegalStateException("beforeWrite ran during delete");
        writes = writes == null ? 1 : writes + 1;
    }

    @Override
    public void afterWrite() {
        if (isDeletionMark()) throw new IllegalStateException("afterWrite ran during delete");
    }

    @Override
    public void beforeDelete() {
        deleteHookCalls++;
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(new BusinessRule("live-only", "Rules ran during delete", () -> !isDeletionMark()));
    }
}
