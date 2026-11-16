package su.onno.query;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class KeysetWindowPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void datesUuidTiesAndDamagedTypesRemainSafeOnPostgres() {
        Jdbi jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbi.useHandle(h -> { h.execute("CREATE TABLE page_dates (_id UUID PRIMARY KEY, d DATE)");
            h.execute("INSERT INTO page_dates VALUES (?, DATE '2026-11-11'), (?, DATE '2026-11-11'), (?, NULL)",
                    new UUID(0,1), new UUID(0,2), new UUID(0,3)); });
        var order = List.of(new KeysetWindow.Order("d", false), new KeysetWindow.Order("_id", false));
        var first = KeysetWindow.fetch(jdbi, "page_dates", "*", "1=1", q -> {}, order, null, 1, "dates");
        var second = KeysetWindow.fetch(jdbi, "page_dates", "*", "1=1", q -> {}, order, first.nextCursor(), 1, "dates");
        var last = KeysetWindow.fetch(jdbi, "page_dates", "*", "1=1", q -> {}, order, second.nextCursor(), 1, "dates");
        assertThat(second.rows().getFirst().get("_id")).isEqualTo(new UUID(0,2));
        assertThat(last.rows().getFirst().get("_id")).isEqualTo(new UUID(0,3));
        assertThat(last.hasMore()).isFalse();
        var context = Cursor.context("dates", order);
        String damaged = Cursor.encodeWindow(context, List.of("not a date", new UUID(0,1)));
        assertThat(KeysetWindow.fetch(jdbi, "page_dates", "*", "1=1", q -> {}, order, damaged, 1, "dates").rows())
                .isEqualTo(first.rows());
    }
}
