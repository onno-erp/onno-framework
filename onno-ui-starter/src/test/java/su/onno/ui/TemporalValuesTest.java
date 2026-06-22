package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for "column is of type date but expression is of type character varying" when editing
 * a catalog/document with a {@code LocalDate}/{@code LocalDateTime} attribute: the value arrives
 * from JSON as a string and must be coerced to a typed temporal so JDBI does not bind a varchar
 * PostgreSQL rejects.
 */
class TemporalValuesTest {

    @Test
    void coercesIsoDateStringToLocalDate() {
        assertThat(TemporalValues.coerce(LocalDate.class, "1996-04-12"))
                .isEqualTo(LocalDate.of(1996, 4, 12));
    }

    @Test
    void coercesDateTimeStringToLocalDateTime() {
        assertThat(TemporalValues.coerce(LocalDateTime.class, "2026-06-04T08:44:44"))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44));
        // H2 hands TIMESTAMP back space-separated; must still parse.
        assertThat(TemporalValues.coerce(LocalDateTime.class, "2026-06-04 08:44:44.417097"))
                .isEqualTo(LocalDateTime.of(2026, 6, 4, 8, 44, 44, 417_097_000));
    }

    @Test
    void passesThroughAlreadyTypedTemporal() {
        LocalDate ld = LocalDate.of(2020, 1, 2);
        assertThat(TemporalValues.coerce(LocalDate.class, ld)).isEqualTo(ld);
    }

    @Test
    void returnsNullForNonTemporalTargetSoCallerLeavesValueAlone() {
        assertThat(TemporalValues.coerce(String.class, "hello")).isNull();
        assertThat(TemporalValues.coerce(BigDecimal.class, "12.34")).isNull();
    }
}
