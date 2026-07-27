package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an owned line-item {@link java.util.List} on a document.
 *
 * <p>Each tabular section must use its own concrete
 * {@link su.onno.model.TabularSectionRow} class. Spring Data JDBC maps a row class to one child
 * table, so startup rejects a concrete row class reused by another document or section. Distinct
 * concrete subclasses may share fields and behavior through a common base class.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface TabularSection {

    String name() default "";
}
