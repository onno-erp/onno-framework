package su.onno.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Ref<>} attribute as the entity's <em>assignee</em>: the user who owns or is responsible
 * for the record. A business fact, declared on the domain class; the web starter's assignment producer
 * reads it ({@code onno.notifications.assignments.enabled}, default true) and, when such a field is set
 * to — or changed to point at — a user, sends that user a notification that the record was assigned
 * to them.
 *
 * <p>Put it on the attribute whose target is (or resolves to) the identity catalog linked by
 * the {@code IdentityLink}. It is purely a notification hint — it does not change the field's storage,
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
