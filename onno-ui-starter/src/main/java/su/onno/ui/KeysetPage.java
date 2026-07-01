package su.onno.ui;

import java.util.List;
import java.util.Map;

/**
 * One keyset-paginated window: the decorated rows plus the {@code nextCursor} a client echoes back
 * to fetch the following window, and whether any rows remain. {@code nextCursor} is {@code null}
 * exactly when {@code hasMore} is false (the end of the list), so a client loops "fetch, render,
 * repeat while nextCursor != null" with no count and no offset arithmetic.
 *
 * @param rows       the decorated rows for this window (refs resolved, secrets redacted)
 * @param nextCursor the opaque cursor for the next window, or {@code null} at the end of the list
 * @param hasMore    whether a further window exists
 */
public record KeysetPage(List<Map<String, Object>> rows, String nextCursor, boolean hasMore) {
}
