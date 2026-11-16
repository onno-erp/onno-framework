package su.onno.history;

/**
 * One changed value inside a {@link HistoryEntry}.
 *
 * <p>A header field change has {@code section == null}. A tabular-section change names the
 * {@code section}, the 1-based {@code line} and how the row changed ({@link #ROW_ADDED},
 * {@link #ROW_REMOVED} or {@link #ROW_CHANGED}); rows are compared by position, so inserting a line
 * in the middle reads as changes to the lines after it.
 *
 * <p>Values are the stored form rendered as text: reference and enumeration ids as UUID strings,
 * dates in ISO-8601, numbers in plain decimal notation. {@code secret} attributes never store
 * values — {@code oldValue}/{@code newValue} are {@code null} and {@code secret} is {@code true}.
 *
 * @param field logical field name ({@code code}, {@code description}, {@code parent},
 *              {@code folder}, {@code number}, {@code date} or an attribute's field name);
 *              {@code null} for a row added or removed without any non-empty value
 */
public record HistoryChange(String section, Integer line, String rowChange, String field,
                            String oldValue, String newValue, boolean secret) {

    public static final String ROW_ADDED = "ADDED";
    public static final String ROW_REMOVED = "REMOVED";
    public static final String ROW_CHANGED = "CHANGED";
}
