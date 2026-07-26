package su.onno.ui;

/**
 * Calendar bucket used when a list opens grouped by a temporal column.
 */
public enum DateGranularity {
    DAY,
    MONTH,
    YEAR;

    /** Lowercase value used by the UI and list grouping API. */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
