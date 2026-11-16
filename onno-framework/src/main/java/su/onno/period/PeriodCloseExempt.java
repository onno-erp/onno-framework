package su.onno.period;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Document} that period close never applies to — an operational record such as a
 * calendar event or a chat message, whose date is not an accounting date. Exempt documents can be
 * written and posted whatever periods are closed, so do not put it on a document whose register
 * movements belong to closed books.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeriodCloseExempt {
}
