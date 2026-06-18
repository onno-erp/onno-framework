package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which roles may read and write an entity. Access is <em>deny by
 * default</em>: an entity with no {@code @AccessControl} (or empty role lists) is
 * invisible and uneditable to every role except the {@code ADMIN} superuser, which
 * bypasses these checks entirely. List only the non-admin roles that should be
 * granted access — there is no need to name {@code ADMIN}.
 *
 * <p>An empty {@code writeRoles} falls back to {@code readRoles}: readers may write
 * unless a stricter write list is given.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface AccessControl {

    String[] readRoles() default {};

    String[] writeRoles() default {};
}
