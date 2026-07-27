package su.onno.ui;

/**
 * Typed feedback and refresh intent returned by an {@link ActionSpec} server handler.
 * Navigation belongs on the declaration itself via {@link ActionSpec.ActionBuilder#navigate(String)}.
 */
public record ActionResult(boolean refresh, ActionFeedback feedback) {

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
