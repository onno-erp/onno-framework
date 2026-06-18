package su.onno.importer;

import java.util.List;
import java.util.Map;

/**
 * Header and sample rows extracted from an uploaded CSV.
 */
public record CsvPreview(
        List<String> headers,
        List<Map<String, String>> rows,
        int totalRows
) {
}
