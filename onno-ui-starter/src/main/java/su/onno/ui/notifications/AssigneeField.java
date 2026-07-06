package su.onno.ui.notifications;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Ref<>} attribute as the entity's <em>assignee</em>: the user who owns or is responsible
 * for the record. When the built-in assignment producer is enabled
 * ({@code onno.notifications.assignments.enabled}, default true) and such a field is set to — or changed
 * to point at — a user, that user is sent a notification that the record was assigned to them.
 *
 * <p>Put it on the attribute whose target is (or resolves to) the identity catalog linked by
 * {@code Layout.identity(...)}. It is purely a notification hint — it does not change the field's storage,
 * validation, or UI — so an app that doesn't use notifications can ignore it:
 *
 * <pre>{@code
 * @Document(name = "Tasks")
 * public class Task extends DocumentObject {
 *     @AssigneeField
 *     @Attribute private Ref<Employee> assignedTo;   // setting this notifies the employee
 * }
 * }</pre>
 *
 * <p>At most one attribute per entity is treated as the assignee; if several are annotated the first
 * declared wins.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AssigneeField {
}
