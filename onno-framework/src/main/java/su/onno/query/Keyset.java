package su.onno.query;

/**
 * The SQL behind keyset (a.k.a. "seek") pagination — the constant-time alternative to
 * {@code LIMIT .. OFFSET ..}. Instead of counting past {@code offset} rows the database discards,
 * the next page is found by <em>seeking</em> straight to the row after a cursor with an indexed
 * comparison, so fetching page 1 and page 10 000 cost the same. The {@code _id} tiebreaker makes
 * the order total, which also fixes offset paging's skip/duplicate bug when rows shift between
 * fetches.
 *
 * <p>Two shapes, chosen by whether the sort column can be null:
 * <ul>
 *   <li><b>Non-null fast path</b> — {@code ORDER BY col DIR, _id DIR} with a plain
 *       {@code (col, _id)} seek. A composite {@code (col, _id)} index serves it as an index-only
 *       range scan in either direction. This is the hot path for the default sorts
 *       ({@code _code}, {@code _date}, {@code _number} — all non-null).</li>
 *   <li><b>NULL-safe path</b> — for a nullable attribute sort, ordering is forced NULLS-LAST with a
 *       leading {@code (col IS NULL)} rank and the seek branches on whether the cursor row sat in
 *       the null tail, so nulls are never skipped or duplicated. Correct, but the leading rank
 *       expression means the composite index can't fully serve the ordering — acceptable for the
 *       rarer nullable-column sort.</li>
 * </ul>
 *
 * <p>The builder only renders SQL; the caller binds {@link #VALUE_BIND} / {@link #ID_BIND} from the
 * cursor (guarded by {@link Plan#bindsValue()}), keeping value handling parameterised and safe.
 */
public final class Keyset {

    /** Default page size when a client doesn't ask for one — small, since keyset favours many cheap pages. */
    public static final int DEFAULT_LIMIT = 50;
    /** Hard ceiling so one request can't pull an unbounded window (mirrors the list API's cap). */
    public static final int MAX_LIMIT = 500;

    /** Reserved bind names for the seek comparison — distinctive so they never collide with a column filter. */
    public static final String VALUE_BIND = "ksSeekValue";
    public static final String ID_BIND = "ksSeekId";

    private Keyset() {
    }

    /** Clamp a requested page size into {@code [1, MAX_LIMIT]}, defaulting a non-positive request. */
    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * The rendered {@code ORDER BY} body and seek predicate for one page.
     *
     * @param orderBy    the {@code ORDER BY} body (no leading keyword), always a total order
     * @param predicate  the seek clause to AND onto the WHERE (leading {@code " AND (..)"}), or
     *                   {@code ""} for the first page
     * @param bindsValue whether {@link #VALUE_BIND} appears in {@code predicate} and must be bound;
     *                   {@link #ID_BIND} is bound whenever a cursor is present
     */
    public record Plan(String orderBy, String predicate, boolean bindsValue) {
        public boolean hasCursor() {
            return !predicate.isEmpty();
        }
    }

    /**
     * Build the page plan for {@code sortColumn} in the given direction, seeking past {@code cursor}
     * (null for the first page). {@code nullable} selects the NULL-safe shape.
     */
    public static Plan plan(String sortColumn, boolean descending, boolean nullable, Cursor cursor) {
        String dir = descending ? "DESC" : "ASC";
        String cmp = descending ? "<" : ">";
        if (nullable) {
            return nullSafePlan(sortColumn, dir, cmp, cursor);
        }
        String orderBy = sortColumn + " " + dir + ", _id " + dir;
        if (cursor == null) {
            return new Plan(orderBy, "", false);
        }
        // Lexicographic "after the cursor": a strictly-greater (or -lesser) sort value, or an equal
        // one with a strictly-greater (or -lesser) id.
        String predicate = " AND (" + sortColumn + " " + cmp + " :" + VALUE_BIND
                + " OR (" + sortColumn + " = :" + VALUE_BIND + " AND _id " + cmp + " :" + ID_BIND + "))";
        return new Plan(orderBy, predicate, true);
    }

    private static Plan nullSafePlan(String sortColumn, String dir, String cmp, Cursor cursor) {
        // Force NULLS LAST in both directions via a leading 0/1 rank, so the contract is stable
        // regardless of dialect defaults (PG: ASC→LAST, DESC→FIRST; H2 differs).
        String nullRank = "(CASE WHEN " + sortColumn + " IS NULL THEN 1 ELSE 0 END)";
        String orderBy = nullRank + " ASC, " + sortColumn + " " + dir + ", _id " + dir;
        if (cursor == null) {
            return new Plan(orderBy, "", false);
        }
        if (cursor.value() == null) {
            // The cursor sat in the null tail; only nulls further along the id order remain.
            String predicate = " AND (" + sortColumn + " IS NULL AND _id " + cmp + " :" + ID_BIND + ")";
            return new Plan(orderBy, predicate, false);
        }
        // The cursor sat among the non-null rows: everything still-greater (or -lesser) by value,
        // the equal-value rows past the id, and the whole null tail.
        String predicate = " AND (" + sortColumn + " IS NULL"
                + " OR " + sortColumn + " " + cmp + " :" + VALUE_BIND
                + " OR (" + sortColumn + " = :" + VALUE_BIND + " AND _id " + cmp + " :" + ID_BIND + "))";
        return new Plan(orderBy, predicate, true);
    }
}
