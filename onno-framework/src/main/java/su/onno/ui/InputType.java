package su.onno.ui;

/**
 * The kind of toolbar input field rendered alongside the custom action buttons. Picks the widget
 * the client shows; the value always reaches a handler as a string (see {@link ActionContext}).
 */
public enum InputType {
    /** A free-text field. */
    TEXT,
    /** A date picker (value is an ISO {@code yyyy-MM-dd} string). */
    DATE,
    /** A numeric field (value is the number as a string). */
    NUMBER,
    /** A dropdown of author-supplied options. */
    SELECT
}
