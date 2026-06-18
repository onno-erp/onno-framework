package su.onno.importer;

import java.util.List;

/**
 * Summary of a CSV import run.
 */
public record ImportResult(
        String entityType,
        String name,
        String mode,
        boolean dryRun,
        int totalRows,
        int created,
        int updated,
        int posted,
        int failed,
        List<ImportRowError> errors
) {
}
