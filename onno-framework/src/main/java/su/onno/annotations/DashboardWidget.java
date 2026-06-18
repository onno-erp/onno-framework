package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Dashboard widget bound to a catalog, document, or register.
 *
 * @deprecated Declare dashboard widgets in a {@code Page} bean instead, e.g.
 * {@snippet :
 *   class DashboardPage implements Page {
 *       public String route() { return "/"; }
 *       public void compose(PageBuilder page) {
 *           page.widget("Recent invoices")
 *               .type("list").order(0).width("1/2")
 *               .document(Invoice.class)
 *               .maxItems(8);
 *       }
 *   }
 * }
 * As of the page/layout migration the dashboard is built from authored pages
 * (surfaced through the DivKit home surface, e.g. {@code /api/divkit/home});
 * this annotation is no longer consulted when the configurer declares any
 * widgets, and will be removed in the next release. (Note: there is no
 * {@code /api/ui/metadata/*} REST endpoint — see the README/AGENTS.md for the
 * real {@code /api/...} surface.)
 */
@Deprecated(since = "next", forRemoval = true)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(DashboardWidgets.class)
public @interface DashboardWidget {
    String title();
    String type();
    int order() default 0;
    String width() default "1/3";
    int maxItems() default 10;
    String dateField() default "";
    String titleField() default "";
    String[] extraConfig() default {};

    /** Optional help text, surfaced as a hoverable {@code ?} icon next to the widget title. */
    String hint() default "";
}
