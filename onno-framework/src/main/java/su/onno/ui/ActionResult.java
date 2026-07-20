package su.onno.ui;

/**
 * What the client does after a server-side action runs: optionally show a message, navigate to a
 * route, and/or refresh the current surface. Returned by an {@link ActionSpec} handler.
 *
 * @param message a toast to show (success), or {@code null}
 * @param navigate an {@code onno://} url to route to afterwards, or {@code null}
 * @param refresh  whether to reload the current list/detail surface
 * @param feedback optional typed feedback; legacy {@code message} remains a success-toast shortcut
 */
public record ActionResult(String message, String navigate, boolean refresh, ActionFeedback feedback) {

    /** Backward-compatible canonical shape used by existing applications. */
    public ActionResult(String message, String navigate, boolean refresh) {
        this(message, navigate, refresh, null);
    }

    /** Did nothing observable — just acknowledge. */
    public static ActionResult ok() {
        return new ActionResult(null, null, false, null);
    }

    /** Show a success toast. */
    public static ActionResult message(String message) {
        return new ActionResult(message, null, false, null);
    }

    /** Show a toast and reload the current surface (the common "it changed data" case). */
    public static ActionResult refresh(String message) {
        return new ActionResult(message, null, true, null);
    }

    /** Route to a destination (e.g. a generated report or a related record). */
    public static ActionResult navigate(String url) {
        return new ActionResult(null, url, false, null);
    }

    /** Show typed feedback without navigation or refresh. */
    public static ActionResult feedback(ActionFeedback feedback) {
        return new ActionResult(null, null, false, feedback);
    }

    /** Show a successful informational/warning acknowledgement dialog. */
    public static ActionResult dialog(ActionDialog dialog) {
        return feedback(dialog.feedback());
    }

    /**
     * Send the top-level browser to an external {@code url} (a full-page navigation, not a new tab) —
     * e.g. kicking off an OAuth "Connect with X" consent screen so the provider can redirect back. The
     * url is passed verbatim after the {@code onno://redirect/} scheme, so it may carry a query string.
     * For plain "show the user an external page" (a marketplace chat, a tracking page), prefer
     * {@link #open(String)} — it opens a new tab and keeps the app where it is.
     */
    public static ActionResult redirect(String url) {
        return new ActionResult(null, "onno://redirect/" + url, false, null);
    }

    /**
     * Open an external {@code url} in a new browser tab ({@code noopener}), leaving the app in place —
     * the right choice for links that merely show something (a buyer chat, an external dashboard).
     * Use {@link #redirect(String)} only when the page must come back (an OAuth round-trip). The url
     * is passed verbatim after the {@code onno://open/} scheme, so it may carry a query string.
     */
    public static ActionResult open(String url) {
        return new ActionResult(null, "onno://open/" + url, false, null);
    }
}
