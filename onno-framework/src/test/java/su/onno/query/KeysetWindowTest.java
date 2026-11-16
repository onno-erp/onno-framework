package su.onno.query;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class KeysetWindowTest {
    private Jdbi jdbi;
    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE feed (a VARCHAR, b INT, owner VARCHAR, PRIMARY KEY (b))");
            for (int i = 1; i <= 8; i++) h.execute("INSERT INTO feed VALUES (?, ?, ?)",
                    i > 5 ? null : "same", i, i == 2 ? "bob" : "alice");
        });
    }
    private KeysetWindow.Page<Map<String, Object>> page(String cursor, boolean descending) {
        return KeysetWindow.fetch(jdbi, "feed", "*", "owner = :owner", q -> q.bind("owner", "alice"),
                List.of(new KeysetWindow.Order("a", descending), new KeysetWindow.Order("b", !descending)),
                cursor, 2, "alice-feed");
    }
    @Test void mixedOrderDuplicatesNullsAndSqlVisibilityAreStable() {
        for (boolean descending : List.of(false, true)) {
            List<Integer> seen = new ArrayList<>();
            String cursor = null;
            do {
                var page = page(cursor, descending);
                page.rows().forEach(r -> seen.add((Integer) r.get("b")));
                assertThat(page.nextCursor() != null).isEqualTo(page.hasMore());
                cursor = page.nextCursor();
            } while (cursor != null);
            assertThat(seen).containsExactlyElementsOf(descending ? List.of(1,3,4,5,6,7,8) : List.of(5,4,3,1,8,7,6));
        }
    }
    @Test void insertionBeforeTheBoundaryDoesNotRepeatOldRows() {
        var first = page(null, true);
        jdbi.useHandle(h -> h.execute("INSERT INTO feed VALUES ('earlier', 0, 'alice')"));
        var next = page(first.nextCursor(), true);
        assertThat(next.rows()).extracting(r -> r.get("b")).containsExactly(4, 5);
    }
    @Test void malformedRestartsAndForeignScopeOrSortIsRejected() {
        assertThat(page("broken", false).rows()).isEqualTo(page(null, false).rows());
        String token = page(null, false).nextCursor();
        assertThatThrownBy(() -> page(token, true)).isInstanceOf(Cursor.ScopeMismatchException.class);
        assertThatThrownBy(() -> KeysetWindow.fetch(jdbi, "feed", "*", "1=1", q -> {},
                List.of(new KeysetWindow.Order("a", false), new KeysetWindow.Order("b", true)), token, 2, "bob-feed"))
                .isInstanceOf(Cursor.ScopeMismatchException.class);
    }
    @Test void wrongSqlTypeRestartsWithoutExecutingAnInvalidComparison() {
        var first = page(null, false);
        String raw = new String(java.util.Base64.getUrlDecoder().decode(first.nextCursor()), java.nio.charset.StandardCharsets.UTF_8);
        String context = new String(java.util.Base64.getUrlDecoder().decode(raw.split("\u001f", -1)[1]), java.nio.charset.StandardCharsets.UTF_8);
        String wrong = Cursor.encodeWindow(context, Arrays.asList(123L, "not-an-integer"));
        assertThat(page(wrong, false).rows()).isEqualTo(first.rows());
    }
    @Test void sqlDatesRemainDatesAndSeekCorrectly() {
        jdbi.useHandle(h -> { h.execute("CREATE TABLE dates (_id UUID PRIMARY KEY, d DATE)");
            h.execute("INSERT INTO dates VALUES (?, DATE '2026-11-10'), (?, DATE '2026-11-11')", new UUID(0, 1), new UUID(0, 2)); });
        var order = List.of(new KeysetWindow.Order("d", false), new KeysetWindow.Order("_id", false));
        var first = KeysetWindow.fetch(jdbi, "dates", "*", "1=1", q -> {}, order, null, 1, "dates");
        var last = KeysetWindow.fetch(jdbi, "dates", "*", "1=1", q -> {}, order, first.nextCursor(), 1, "dates");
        assertThat(last.rows()).extracting(r -> r.get("_id")).containsExactly(new UUID(0, 2));
        var date = java.sql.Date.valueOf("2026-11-11");
        assertThat(Cursor.decodeWindow(Cursor.encodeWindow("date", List.of(date)), "date", 1).values().getFirst()).isEqualTo(date);
    }

    @Test void tupleCodecPreservesSeparatorsNullsAndTemporalPrecision() {
        var values = Arrays.<Object>asList("text\u001fwith separator", null, java.sql.Timestamp.valueOf("2026-11-10 12:30:20.123456"));
        String token = Cursor.encodeWindow("context", values);
        assertThat(Cursor.decodeWindow(token, "context", 3).values()).isEqualTo(values);
        assertThat(KeysetWindow.clampLimit(0)).isEqualTo(100);
        assertThat(KeysetWindow.clampLimit(10000)).isEqualTo(500);
    }
}
