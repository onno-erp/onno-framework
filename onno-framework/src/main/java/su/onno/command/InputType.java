package su.onno.command;

/**
 * The kind of value a command input takes. The value always reaches the handler as a string (see
 * {@link CommandContext#input(String)}).
 */
public enum InputType {
    /** Single-line free text. */
    TEXT,
    /** Free text that may run to several lines (a reason, a note), as opposed to a short value. */
    LONG_TEXT,
    /** An ISO {@code yyyy-MM-dd} date. */
    DATE,
    /** A number, as its decimal string. */
    NUMBER,
    /** One of the input's declared options. */
    SELECT,
    /**
     * The id (UUID string) of a record of another catalog or document, declared with
     * {@link InputBuilder#reference(Class)}.
     */
    REFERENCE;

    /** The JSON name of this type: {@code text}, {@code longText}, {@code date}, … */
    public String wireValue() {
        return this == LONG_TEXT ? "longText" : name().toLowerCase();
    }
}
