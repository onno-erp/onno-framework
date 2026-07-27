package su.onno.annotations;

import su.onno.model.AccumulationType;
import su.onno.model.PostingOrder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AccumulationRegister {

    String name();

    /**
     * Human-facing display label, distinct from the URL-safe {@link #name()} identity.
     * Use it for localized or multi-word titles while keeping {@code name} ASCII and
     * space-free so routes stay clean. Surfaces in the UI metadata and is used for nav
     * items and report headings. When empty, falls back to {@link #name()}.
     */
    String title() default "";

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";

    AccumulationType type() default AccumulationType.BALANCE;

    /**
     * Whether a {@link AccumulationType#BALANCE} register may retain negative resource totals.
     * Keep this disabled for constrained balances such as inventory; enable it for balances
     * whose domain permits debt or overdrafts, such as cash or credit accounts.
     * Ignored for {@link AccumulationType#TURNOVER} registers.
     */
    boolean allowNegative() default false;

    /**
     * Controls whether a backdated post/unpost restores later documents that wrote to this register.
     * Use {@link PostingOrder#CHRONOLOGICAL} when later posting calculations depend on earlier
     * register state, such as moving-average inventory cost.
     */
    PostingOrder postingOrder() default PostingOrder.INDEPENDENT;

    String context() default "";
}
