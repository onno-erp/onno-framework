package su.onno.pagination;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.query.Cursor;
import su.onno.web.KeysetWindow;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the reusable API from outside the UI package, with an SQL visibility scope. */
class KeysetWindowPublicApiTest {
    @Test
    void publicWindowPreservesScopeAndRejectsForeignCursors() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:publicWindow" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);
        jdbi.useHandle(h -> {
            h.execute("CREATE TABLE feed (_id UUID PRIMARY KEY, sort_value VARCHAR, owner VARCHAR)");
            for (int i = 1; i <= 4; i++) {
                h.createUpdate("INSERT INTO feed VALUES (:id, 'same', :owner)")
                        .bind("id", new UUID(0, i)).bind("owner", i == 2 ? "bob" : "alice").execute();
            }
        });
        var first = fetch(jdbi, null);
        assertThat(first.rows()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.decorated().nextCursor()).isEqualTo(first.nextCursor());
        var second = fetch(jdbi, first.nextCursor());
        assertThat(second.rows().getFirst().get("_id")).isEqualTo(new UUID(0, 3));
        var last = fetch(jdbi, second.nextCursor());
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(fetch(jdbi, "broken~alice").rows()).isEqualTo(first.rows());
        String foreign = Cursor.from("sort_value", false, first.rows().getFirst()).encode("bob");
        assertThatThrownBy(() -> fetch(jdbi, foreign))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private KeysetWindow.Raw fetch(Jdbi jdbi, String cursor) {
        return KeysetWindow.fetch(jdbi, "feed", "owner = :owner", q -> q.bind("owner", "alice"),
                "sort_value", false, true, new KeysetWindow.Position(cursor, null, null), 1, "alice");
    }
}
