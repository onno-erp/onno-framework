package su.onno.importer;

/**
 * One failed CSV row, using 1-based data-row numbering (header is not counted).
 */
public record ImportRowError(
        int row,
        String message
) {
}
