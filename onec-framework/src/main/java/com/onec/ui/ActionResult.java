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
}
