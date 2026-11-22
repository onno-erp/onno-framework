package su.onno.period;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Document} type as an adjustment document: it may be created, changed, posted
 * and unposted inside a <em>soft</em>-closed period without reopening it. Every such change is
 * written to the audit trail as an {@link PeriodCloseAction#ADJUSTMENT}.
 *
 * <p>Only the adjustment document itself is admitted — every other document stays locked. A hard
 * close admits nothing, and a backdated adjustment whose chronological restoration would repost a
 * non-adjustment document inside a closed period is still rejected. Restrict who may write the
 * document type in the application's {@code RoleGrants} as usual.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeriodAdjustment {
}
