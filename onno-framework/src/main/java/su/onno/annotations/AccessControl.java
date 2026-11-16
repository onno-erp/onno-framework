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
 *
 * <p>On a document, {@code postRoles} and {@code unpostRoles} separate the right to post
 * (and re-post) or unpost from the right to edit. Each is a <em>narrowing</em> gate on top of
 * write access: the caller must be able to write the document <em>and</em> hold one of the
 * listed roles. Left empty, every writer may post / unpost (the pre-existing behaviour).
 * {@code ADMIN} always passes. The gates apply to every user-facing path (generic REST, MCP
 * tools, the built-in UI actions, and deleting a posted document, which unposts it); trusted
 * application code calling {@code PostingService} directly is not gated, like typed repositories.
 *
 * <pre>{@code
 * @Document(name = "Invoices")
 * @AccessControl(readRoles = {"RECEPTION", "MANAGER"}, unpostRoles = "MANAGER")
 * public class Invoice extends DocumentObject implements Postable { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface AccessControl {

    String[] readRoles() default {};

    String[] writeRoles() default {};

    /**
     * Documents only: roles that may post (and re-post) the document, in addition to holding
     * write access. Empty means every writer may post.
     */
    String[] postRoles() default {};

    /**
     * Documents only: roles that may unpost the document — including deleting a posted document,
     * which unposts it first — in addition to holding write access. Empty means every writer may
     * unpost.
     */
    String[] unpostRoles() default {};
}
