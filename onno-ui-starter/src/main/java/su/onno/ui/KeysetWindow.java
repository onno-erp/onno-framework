package su.onno.ui;

import su.onno.query.Cursor;
import su.onno.query.Keyset;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Public SQL pagination helper shared by collection query services. Table and column names must
 * come from validated metadata, never directly from request parameters. The binder must apply
 * the same access scope to every query, including focus lookup.
 *
 * <p>Fetches one keyset window of raw (undecorated) rows for the catalog and document list feeds, so
 * both share one seek algorithm. A window is requested at one of three positions:
 * <ul>
 *   <li><b>forward</b> — the first window, or the rows after a {@code cursor};</li>
 *   <li><b>backward</b> — the rows immediately before a {@code before} cursor (the window above the
 *       one a client already shows), in list order;</li>
 *   <li><b>focus</b> — the window that <em>contains</em> a given record: up to half the limit of
 *       rows before it, the record, and the rows after it, so "open in list" lands on a record
 *       however deep it sits. A record the query cannot see (outside the search/filters/record
 *       scope, deletion-marked, or unknown) falls back to the first window without a focus — the
 *       same answer for every case, so the feed never reveals whether a hidden record exists.</li>
 * </ul>
 * A {@code cursor} wins over {@code before}, which wins over {@code focus}. Cursors are minted from
 * the raw rows and sealed with the subject's fingerprint, exactly like the forward feed's.
 */
public final class KeysetWindow {

    /** Bind name of the focused record's id — distinctive so it never collides with a filter bind. */
    static final String FOCUS_BIND = "ksFocusId";

    private KeysetWindow() {
    }

    /**
     * The requested position of a window.
     *
     * @param cursorToken the sealed {@code nextCursor} to continue after, or {@code null}
     * @param beforeToken the sealed {@code prevCursor} to load the rows above, or {@code null}
     * @param focus       the record whose surrounding window to return, or {@code null}
     */
    public record Position(String cursorToken, String beforeToken, UUID focus) {
    }

    /** A window of raw rows plus its bounds — the service decorates the rows into a {@link KeysetPage}. */
    public record Raw(List<Map<String, Object>> rows, String nextCursor, boolean hasMore,
               String prevCursor, boolean hasPrevious, UUID focus) {
        public KeysetPage decorated() {
            return new KeysetPage(rows, nextCursor, hasMore, prevCursor, hasPrevious, focus);
        }
    }

    /**
     * Fetch the window at {@code position}.
     *
     * @param where      the full live-row predicate (deletion mark, search, filters, record scope),
     *                   without the seek clause
     * @param binder     binds every parameter {@code where} references
     * @param column     the validated sort column
     * @param nullable   whether {@code column} needs the NULL-safe seek shape
     */
    public static Raw fetch(Jdbi jdbi, String table, String where, Consumer<Query> binder,
                     String column, boolean descending, boolean nullable,
                     Position position, int limit, String fingerprint) {
        int lim = Keyset.clampLimit(limit);
        Seek seek = new Seek(jdbi, table, where, binder, column, descending, nullable, fingerprint);
        if (present(position.cursorToken())) {
            return seek.forward(seek.decode(position.cursorToken()), lim);
        }
        if (present(position.beforeToken())) {
            Cursor before = seek.decode(position.beforeToken());
            // A malformed position restarts from the top; a different cursor context is rejected.
            return before == null ? seek.forward(null, lim) : seek.backward(before, lim);
        }
        if (position.focus() != null) {
            Raw focused = seek.around(position.focus(), lim);
            if (focused != null) {
                return focused;
            }
        }
        return seek.forward(null, lim);
    }

    /** Forward collection window, including composite keys for register totals. */
    public static su.onno.query.KeysetWindow.Page<Map<String, Object>> collection(
            Jdbi jdbi, String source, String projection, String where, Consumer<Query> binder,
            List<su.onno.query.KeysetWindow.Order> order, String token, int limit, String context) {
        try {
            return su.onno.query.KeysetWindow.fetch(jdbi, source, projection, where, binder,
                    order, token, limit, context);
        } catch (Cursor.ScopeMismatchException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Cursor belongs to a different feed or access scope", e);
        }
    }

    /** The common collection wire envelope. */
    public static <T> Map<String, Object> envelope(su.onno.query.KeysetWindow.Page<T> page) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", page.rows());
        out.put("nextCursor", page.nextCursor());
        out.put("hasMore", page.hasMore());
        return out;
    }

    private static boolean present(String token) {
        return token != null && !token.isBlank();
    }

    /** One window request's query context. */
    private record Seek(Jdbi jdbi, String table, String where, Consumer<Query> binder,
                        String column, boolean descending, boolean nullable, String fingerprint) {

        Cursor decode(String token) {
            try {
                var decoded = Cursor.decodeWindow(token, cursorContext(), 2);
                if (decoded == null) return null;
                boolean valid = jdbi.withHandle(h -> su.onno.query.KeysetWindow.validPosition(h, table, "*", where,
                        binder, order(), decoded.values()));
                if (!valid) return null;
                return new Cursor(column, descending, decoded.values().get(0), (UUID) decoded.values().get(1));
            } catch (Cursor.ScopeMismatchException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage(), e);
            }
        }

        Raw forward(Cursor cursor, int lim) {
            List<Map<String, Object>> rows = select(Keyset.plan(column, descending, nullable, cursor), cursor, lim + 1);
            boolean hasMore = rows.size() > lim;
            if (hasMore) {
                rows.removeLast();
            }
            return new Raw(rows, hasMore ? seal(rows.getLast()) : null, hasMore, null, false, null);
        }

        Raw backward(Cursor before, int lim) {
            List<Map<String, Object>> rows = select(Keyset.planBefore(column, descending, nullable, before), before,
                    lim + 1);
            boolean hasPrevious = rows.size() > lim;
            if (hasPrevious) rows.removeLast();
            Collections.reverse(rows);
            if (rows.isEmpty()) {
                return new Raw(rows, null, false, null, false, null);
            }
            // The rows after this window are the ones the client already shows; nextCursor still
            // continues forward from its last row, so the envelope keeps its usual meaning.
            return new Raw(rows, seal(rows.getLast()), true,
                    hasPrevious ? seal(rows.getFirst()) : null, hasPrevious, null);
        }

        /** The window containing {@code id}, or {@code null} when the query cannot see that record. */
        Raw around(UUID id, int lim) {
            java.util.Optional<Map<String, Object>> focused = jdbi.withHandle(h -> {
                Query q = h.createQuery("SELECT * FROM " + table + " WHERE " + where
                        + " AND _id = :" + FOCUS_BIND);
                binder.accept(q);
                return q.bind(FOCUS_BIND, id).mapToMap().findOne();
            });
            if (focused.isEmpty()) {
                return null;
            }
            Map<String, Object> focus = focused.get();
            Cursor at = Cursor.from(column, descending, focus);
            // Fetch up to a full limit on each side so a short tail can be balanced with more rows
            // above (and vice versa), then split the limit around the record.
            List<Map<String, Object>> after = select(Keyset.plan(column, descending, nullable, at), at, lim);
            List<Map<String, Object>> before = select(Keyset.planBefore(column, descending, nullable, at), at, lim);
            int beforeTake = Math.clamp(Math.max((lim - 1) / 2, lim - 1 - after.size()), 0, before.size());
            int afterTake = Math.min(after.size(), lim - 1 - beforeTake);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = beforeTake - 1; i >= 0; i--) rows.add(before.get(i));
            rows.add(focus);
            for (int i = 0; i < afterTake; i++) rows.add(after.get(i));
            boolean hasPrevious = before.size() > beforeTake;
            boolean hasMore = after.size() > afterTake;
            return new Raw(rows,
                    hasMore ? seal(rows.getLast()) : null, hasMore,
                    hasPrevious ? seal(rows.getFirst()) : null, hasPrevious,
                    id);
        }

        private List<su.onno.query.KeysetWindow.Order> order() {
            return List.of(new su.onno.query.KeysetWindow.Order(column, descending),
                    new su.onno.query.KeysetWindow.Order("_id", descending));
        }

        private String cursorContext() { return Cursor.context("entity", table, fingerprint, order()); }

        private String seal(Map<String, Object> row) {
            return Cursor.encodeWindow(cursorContext(), java.util.Arrays.asList(row.get(column), row.get("_id")));
        }

        private List<Map<String, Object>> select(Keyset.Plan plan, Cursor cursor, int n) {
            return jdbi.withHandle(h -> {
                Query q = h.createQuery("SELECT * FROM " + table
                                + " WHERE " + where + plan.predicate()
                                + " ORDER BY " + plan.orderBy()
                                + " LIMIT :limit")
                        .bind("limit", n);
                binder.accept(q);
                if (plan.hasCursor()) {
                    q.bind(Keyset.ID_BIND, cursor.id());
                    if (plan.bindsValue()) q.bind(Keyset.VALUE_BIND, cursor.value());
                }
                return new ArrayList<>(q.mapToMap().list());
            });
        }
    }
}
