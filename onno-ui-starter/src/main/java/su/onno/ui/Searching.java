package su.onno.ui;

import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds the per-column SQL term for a list's free-text ({@code q}) search — shared by the catalog and
 * document feeds so both search the same way. The search spans <em>every</em> non-secret column, not
 * just strings:
 *
 * <ul>
 *   <li>scalar columns (strings, numbers, dates, booleans) — matched as text ({@code LOWER(CAST(col AS
 *       VARCHAR)) LIKE :search});</li>
 *   <li>{@code Ref<>} columns — matched by the <em>displayed</em> value of the target row (a catalog's
 *       description/code, a document's number) via a correlated {@code EXISTS} subquery, so typing a
 *       customer's name finds their orders rather than needing the raw UUID;</li>
 *   <li>enum columns — matched by the value's label or constant name, resolved to the matching value
 *       ids from metadata.</li>
 * </ul>
 *
 * <p>The one bound {@code :search} parameter ({@code %term%}, lowercased) drives every text/ref term;
 * enum matches inline their value ids, which come from trusted metadata (never user input) and so are
 * safe to embed directly.
 */
final class Searching {

    private Searching() {
    }

    /**
     * One OR-term matching {@code search} against attribute {@code a}, or {@code null} when the
     * attribute can contribute nothing to the match (an enum none of whose values match the term).
     */
    static String term(MetadataRegistry registry, AttributeDescriptor a, String search) {
        String col = a.columnName();
        if (a.isRef() && a.refTarget() != null) {
            RefTarget rt = refTarget(registry, a.refTarget());
            if (rt == null) {
                return likeVarchar(col); // unknown target — fall back to matching the raw ref column
            }
            String display = rt.displayCols().stream()
                    .map(dc -> "LOWER(CAST(t." + dc + " AS VARCHAR)) LIKE :search")
                    .collect(Collectors.joining(" OR "));
            return "EXISTS (SELECT 1 FROM " + rt.table() + " t WHERE t._id = " + col + " AND (" + display + "))";
        }
        if (a.javaType().isEnum()) {
            List<String> ids = enumIdsMatching(registry, a.javaType(), search);
            if (ids.isEmpty()) {
                return null;
            }
            String inList = ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
            // Cast the enum's UUID column to text so the id-literal comparison is dialect-agnostic
            // (avoids Postgres uuid/text coercion ambiguity); UUID.toString() is the lowercase form.
            return "CAST(" + col + " AS VARCHAR) IN (" + inList + ")";
        }
        return likeVarchar(col);
    }

    /** The text-cast LIKE term for one column, driven by the bound {@code :search} parameter. */
    static String likeVarchar(String column) {
        return "LOWER(CAST(" + column + " AS VARCHAR)) LIKE :search";
    }

    private record RefTarget(String table, List<String> displayCols) {}

    /** Resolve a ref's registered logical name to its table + the column(s) shown for a target row. */
    private static RefTarget refTarget(MetadataRegistry registry, String logicalName) {
        for (CatalogDescriptor c : registry.allCatalogs()) {
            if (c.logicalName().equals(logicalName)) {
                return new RefTarget(c.tableName(), List.of("_description", "_code"));
            }
        }
        for (DocumentDescriptor d : registry.allDocuments()) {
            if (d.logicalName().equals(logicalName)) {
                return new RefTarget(d.tableName(), List.of("_number"));
            }
        }
        return null;
    }

    /** The ids of the enum's values whose label or constant name contains the search term. */
    private static List<String> enumIdsMatching(MetadataRegistry registry, Class<?> enumType, String search) {
        String needle = search.toLowerCase(Locale.ROOT);
        return registry.allEnumerations().stream()
                .filter(e -> e.javaClass().equals(enumType))
                .findFirst()
                .map(e -> e.values().stream()
                        .filter(v -> contains(v.label(), needle) || contains(v.name(), needle))
                        .map(v -> v.id().toString())
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    private static boolean contains(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
