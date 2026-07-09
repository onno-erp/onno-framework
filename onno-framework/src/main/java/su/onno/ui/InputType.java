package su.onno.ui;

/**
 * The kind of toolbar input field rendered alongside the custom action buttons. Picks the widget
 * the client shows; the value always reaches a handler as a string (see {@link ActionContext}).
 */
public enum InputType {
    /** A free-text field. */
    TEXT,
    /** A multi-line free-text field (action-form dialogs; a toolbar renders it as a single-line field). */
    TEXTAREA,
    /** A date picker (value is an ISO {@code yyyy-MM-dd} string). */
    DATE,
    /** A numeric field (value is the number as a string). */
    NUMBER,
    /** A dropdown of author-supplied options. */
    SELECT,
    /**
     * A searchable picker of another catalog/document's records — the same ref widget an entity form
     * uses. The value is the picked record's id (a UUID string); the target entity is declared with
     * {@link InputSpec.InputBuilder#reference(Class)}. Action forms only.
     */
    REFERENCE
}
