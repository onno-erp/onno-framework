package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-field UI hint.
 *
 * @deprecated Configure field hints in an {@code EntityView} or {@code Layout}
 * bean instead, e.g.
 * {@snippet :
 *   class InvoiceView implements EntityView {
 *       public Class<?> entity() { return Invoice.class; }
 *       public void fields(EntityConfigBuilder f) {
 *           f.field("total").order(10).hideInForm()
 *            .field("notes").widget("textarea");
 *       }
 *   }
 * }
 * Layout-configured hints override this annotation. The annotation will be
 * removed in the next release.
 *
 * <p><b>One exception:</b> tabular section row classes (e.g. line-item rows
 * inside a document's {@code @TabularSection}). The DSL does not yet expose
 * tabular section field hints; use {@code @UiHint} on those row classes only
 * when custom row-field hints are still required.</p>
 */
@Deprecated(since = "next", forRemoval = true)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface UiHint {
    boolean visibleInList() default true;
    boolean visibleInForm() default true;
    boolean visibleInDetail() default true;
    int order() default 0;
    String group() default "";
    String width() default "";

    /**
     * Controls the input widget rendered in the form.
     * Values: "" (default input), "textarea", "richtext"
     */
    String widget() default "";
}
