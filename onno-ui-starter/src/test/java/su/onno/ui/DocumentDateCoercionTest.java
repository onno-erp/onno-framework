package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression for "Failed to reconstruct document: Text '2026-06-04 08:44:44.417097' could not be
 * parsed at index 10" on repost: a TIMESTAMP column comes back as a {@link Timestamp} (or a
 * space-separated string), which a strict {@code LocalDateTime.parse} rejected at the space.
 */
class DocumentDateCoercionTest {

    @Test
    void localDateTime_fromSqlTimestamp() {
        Timestamp ts = Timestamp.valueOf("2026-06-04 08:44:44.417097");
        assertThat(DocumentCommandService.toLocalDateTime(ts))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44, 417_097_000));
    }

    @Test
    void localDateTime_fromSpaceSeparatedString() {
        assertThat(DocumentCommandService.toLocalDateTime("2026-06-04 08:44:44.417097"))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44, 417_097_000));
    }

    @Test
    void localDateTime_fromIsoStringAndInstance() {
        assertThat(DocumentCommandService.toLocalDateTime("2026-06-04T08:44:44"))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44));
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 2, 3, 4);
        assertThat(DocumentCommandService.toLocalDateTime(ldt)).isEqualTo(ldt);
    }

    @Test
    void localDate_fromTimestampDateAndStrings() {
        LocalDate expected = LocalDate.of(2026, 6, 4);
        assertThat(DocumentCommandService.toLocalDate(Timestamp.valueOf("2026-06-04 08:44:44.4")))
                .isEqualTo(expected);
        assertThat(DocumentCommandService.toLocalDate(java.sql.Date.valueOf("2026-06-04")))
                .isEqualTo(expected);
        assertThat(DocumentCommandService.toLocalDate("2026-06-04 08:44:44.417097")).isEqualTo(expected);
        assertThat(DocumentCommandService.toLocalDate("2026-06-04")).isEqualTo(expected);
    }

    @Test
    void doesNotThrowOnTheReportedValue() {
        assertThatCode(() -> DocumentCommandService.toLocalDateTime("2026-06-04 08:44:44.417097"))
                .doesNotThrowAnyException();
    }
}
