package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Human-facing display label for a single {@code @Enumeration} constant, distinct from the Java
 * constant name. Put it on the constant to localize or pretty-print the value
 * (e.g. {@code @EnumLabel("Новый") NEW}) <em>without</em> renaming the constant — the name stays
 * part of the data/API contract (it derives the value's stable UUID and is mirrored by importers
 * and list filters), while the label is what users see.
 *
 * <p>Surfaces wherever an enum value is displayed: {@code {column}_display} in the read API, the
 * {@code label} of each entry in an attribute's {@code enumValues} metadata, and the form dropdown.
 * When absent (or empty), the display falls back to the constant name.
 *
 * <pre>{@code
 * @Enumeration(name = "OrderStatuses", title = "Статусы заказов")
 * public enum OrderStatus {
 *     @EnumLabel("Новый") NEW,
 *     @EnumLabel("Отгружен") SHIPPED
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EnumLabel {

    String value();
}
