package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sidebar section assignment for an entity.
 *
 * @deprecated Assign entities to sidebar sections in a {@code Layout} bean
 * instead, e.g.
 * {@snippet :
 *   class MainLayout implements Layout {
 *       public void configure(LayoutSpec layout) {
 *           layout.section("Sales").order(0).icon("euro")
 *           .document(Invoice.class)
 *           .catalog(Customer.class);
 *       }
 *   }
 * }
 * Layout beans are the source of truth for the sidebar (served at
 * {@code /api/ui/metadata/layout}); this annotation is currently echoed only
 * onto the per-entity metadata response as a {@code section} field that the
 * React frontend does not consume. The annotation will be removed in the next
 * release.
 */
@Deprecated(since = "next", forRemoval = true)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UiSection {
    String value();
    int order() default 0;
}
