package su.onno.print;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a print template attached to a document or catalog class.
 * Multiple templates can be declared on the same target (e.g. summary, detailed, legal).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(PrintTemplates.class)
public @interface PrintTemplate {

    /** Stable identifier used in URLs and UI actions, e.g. "bill", "guardia-civil". */
    String name();

    /** Human-readable label for the print action button. */
    String label() default "";

    /**
     * Template path. Resolved by Thymeleaf's resource loader.
     * Default convention: {@code classpath:/print/{name}.html}.
     */
    String template() default "";

    /** Render format. */
    PrintFormat format() default PrintFormat.PDF;

    /** Order in UI lists when multiple templates are defined. */
    int order() default 0;
}
