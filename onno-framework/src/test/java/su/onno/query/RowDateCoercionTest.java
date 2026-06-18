package su.onno.query;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression for "Text '2026-06-04 08:44:44.417097' could not be parsed at index 10" when a
 * report/register query selects a LocalDateTime column: H2 hands the value back as a
 * {@link Timestamp} or a space-separated string, which a strict {@code LocalDateTime.parse}
 * rejected at the space. Mirrors {@code DocumentDateCoercionTest} in onno-ui-starter, covering
 * both coercion paths in the typed query layer: {@link Row#getDateTime} and
 * {@link RowMapper#coerce}.
 */
class RowDateCoercionTest {

    private static final LocalDateTime EXPECTED =
            LocalDateTime.of(2026, 6, 4, 8, 44, 44, 417_097_000);

    @Test
    void rowGetDateTime_fromSqlTimestamp() {
        Row row = new Row(Map.of("ts", Timestamp.valueOf("2026-06-04 08:44:44.417097")));
        assertThat(row.getDateTime("ts")).isEqualTo(EXPECTED);
    }

    @Test
    void rowGetDateTime_fromSpaceSeparatedString() {
        Row row = new Row(Map.of("ts", "2026-06-04 08:44:44.417097"));
        assertThat(row.getDateTime("ts")).isEqualTo(EXPECTED);
    }

    @Test
    void rowGetDateTime_fromIsoStringAndInstance() {
        assertThat(new Row(Map.of("ts", "2026-06-04T08:44:44")).getDateTime("ts"))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44));
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 2, 3, 4);
        assertThat(new Row(Map.of("ts", ldt)).getDateTime("ts")).isEqualTo(ldt);
    }

    @Test
    void rowGetDateTime_doesNotThrowOnReportedValue() {
        Row row = new Row(Map.of("ts", "2026-06-04 08:44:44.417097"));
        assertThatCode(() -> row.getDateTime("ts")).doesNotThrowAnyException();
    }

    @Test
    void coerce_fromSqlTimestamp() {
        assertThat(RowMapper.coerce(Timestamp.valueOf("2026-06-04 08:44:44.417097"), LocalDateTime.class))
                .isEqualTo(EXPECTED);
    }

    @Test
    void coerce_fromSpaceSeparatedString() {
        assertThat(RowMapper.coerce("2026-06-04 08:44:44.417097", LocalDateTime.class))
                .isEqualTo(EXPECTED);
    }

    @Test
    void coerce_fromIsoStringAndInstance() {
        assertThat(RowMapper.coerce("2026-06-04T08:44:44", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44));
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 2, 3, 4);
        assertThat(RowMapper.coerce(ldt, LocalDateTime.class)).isEqualTo(ldt);
    }
}
