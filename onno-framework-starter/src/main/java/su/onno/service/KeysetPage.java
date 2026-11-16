package su.onno.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One keyset-paginated window: the decorated rows plus the {@code nextCursor} a client echoes back
 * to fetch the following window, and whether any rows remain. {@code nextCursor} is {@code null}
 * exactly when {@code hasMore} is false (the end of the list), so a client loops "fetch, render,
 * repeat while nextCursor != null" with no count and no offset arithmetic.
 *
 * <p>A window that does not start at the top of the list — one anchored on a focused record, or a
 * backward window fetched with a {@code before} cursor — also carries {@code prevCursor}: the
 * opaque cursor of its first row, replayed as {@code before} to load the rows above it.
 * {@code prevCursor} is {@code null} exactly when {@code hasPrevious} is false.
 *
 * @param rows        the decorated rows for this window (refs resolved, secrets redacted)
 * @param nextCursor  the opaque cursor for the next window, or {@code null} at the end of the list
 * @param hasMore     whether a further window exists
 * @param prevCursor  the opaque cursor for the window above this one, or {@code null} at the top
 * @param hasPrevious whether rows exist above this window
 * @param focus       the record the window was anchored on (it is among {@code rows}), or
 *                    {@code null} when no focus was requested or the record is not in the list
 */
public record KeysetPage(List<Map<String, Object>> rows, String nextCursor, boolean hasMore,
                         String prevCursor, boolean hasPrevious, UUID focus) {

    /** A forward window from the top of the list (or past a {@code cursor}). */
    public KeysetPage(List<Map<String, Object>> rows, String nextCursor, boolean hasMore) {
        this(rows, nextCursor, hasMore, null, false, null);
    }

    /** The same window positions over replacement rows (e.g. after re-representing them). */
    public KeysetPage withRows(List<Map<String, Object>> replacement) {
        return new KeysetPage(replacement, nextCursor, hasMore, prevCursor, hasPrevious, focus);
    }
}
