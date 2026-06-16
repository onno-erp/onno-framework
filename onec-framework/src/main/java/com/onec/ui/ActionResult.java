package com.onec.ui;

/**
 * What the client does after a server-side action runs: optionally show a message, navigate to a
 * route, and/or refresh the current surface. Returned by an {@link ActionSpec} handler.
 *
 * @param message a toast to show (success), or {@code null}
 * @param navigate an {@code onec://} url to route to afterwards, or {@code null}
 * @param refresh  whether to reload the current list/detail surface
 */
public record ActionResult(String message, String navigate, boolean refresh) {

    /** Did nothing observable — just acknowledge. */
    public static ActionResult ok() {
        return new ActionResult(null, null, false);
    }

    /** Show a success toast. */
    public static ActionResult message(String message) {
        return new ActionResult(message, null, false);
    }

    /** Show a toast and reload the current surface (the common "it changed data" case). */
    public static ActionResult refresh(String message) {
        return new ActionResult(message, null, true);
    }

    /** Route to a destination (e.g. a generated report or a related record). */
    public static ActionResult navigate(String url) {
        return new ActionResult(null, url, false);
    }

    /**
     * Send the top-level browser to an external {@code url} (a full-page navigation, not a new tab) —
     * e.g. kicking off an OAuth "Connect with X" consent screen so the provider can redirect back. The
     * url is passed verbatim after the {@code onec://redirect/} scheme, so it may carry a query string.
     */
    public static ActionResult redirect(String url) {
        return new ActionResult(null, "onec://redirect/" + url, false);
    }
}
