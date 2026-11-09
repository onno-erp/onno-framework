package su.onno.ui;

import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.types.Ref;

import java.util.Locale;
import java.util.Objects;

/**
 * Typed feedback, refresh intent and optional navigation returned by an {@link ActionSpec} handler.
 * Use {@link #open(Ref)} when the handler creates a record, or {@link #navigate(String)} for a page.
 * Fixed navigation without a handler remains {@link ActionSpec.ActionBuilder#navigate(String)}.
 */
public record ActionResult(boolean refresh, ActionFeedback feedback, String navigateTo) {

    public ActionResult {
        if (navigateTo != null && !navigateTo.startsWith("onno://")
                && !(navigateTo.startsWith("/") && !navigateTo.startsWith("//") && !navigateTo.contains("\\"))) {
            throw new IllegalArgumentException("navigateTo must be an onno:// URL or an application path starting with /");
        }
    }

    /** Constructor for a result without navigation. */
    public ActionResult(boolean refresh, ActionFeedback feedback) {
        this(refresh, feedback, null);
    }

    /** Open a catalog or document record through the workspace's normal pane/tab routing. */
    public static ActionResult open(Ref<?> ref) {
        Objects.requireNonNull(ref, "ref is required");
        Catalog catalog = ref.type().getAnnotation(Catalog.class);
        Document document = ref.type().getAnnotation(Document.class);
        if ((catalog == null) == (document == null)) {
            throw new IllegalArgumentException("Reference target must declare exactly one of @Catalog or @Document: "
                    + ref.type().getName());
        }
        String kind = catalog != null ? "catalogs" : "documents";
        String name = catalog != null ? catalog.name() : document.name();
        // Match client record links; storage table overrides and display titles are not route names.
        String routeName = name.replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
        return navigate("/" + kind + "/" + routeName + "/" + ref.id());
    }

    /** Navigate to a router-relative path or an onno:// URL after a successful handler. */
    public static ActionResult navigate(String route) {
        return new ActionResult(false, null, Objects.requireNonNull(route, "route is required"));
    }

    /** Did nothing observable — just acknowledge. */
    public static ActionResult ok() {
        return new ActionResult(false, null);
    }

    /** Reload the current surface without showing feedback. */
    public static ActionResult reload() {
        return new ActionResult(true, null);
    }

    /** Show typed feedback without navigation or refresh. */
    public static ActionResult feedback(ActionFeedback feedback) {
        return new ActionResult(false, feedback);
    }

    /** Show a successful informational/warning acknowledgement dialog. */
    public static ActionResult dialog(ActionDialog dialog) {
        return feedback(dialog.feedback());
    }

    /** Show a structured toast with an explicit tone, heading, explanation, and optional details. */
    public static ActionResult toast(ActionToast toast) {
        return feedback(toast.feedback());
    }

    /** Reload the current surface and show a structured toast. */
    public static ActionResult refresh(ActionToast toast) {
        return new ActionResult(true, toast.feedback());
    }

    /** Reload the current surface and show an acknowledgement dialog. */
    public static ActionResult refresh(ActionDialog dialog) {
        return new ActionResult(true, dialog.feedback());
    }
}
