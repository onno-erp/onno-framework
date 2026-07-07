package su.onno.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KeysetCursorTest {

    private static Map<String, Object> row(String col, Object value, UUID id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(col, value);
        r.put("_id", id);
        return r;
    }

    @Test
    void roundTripsAStringValue() {
        UUID id = UUID.randomUUID();
        Cursor c = Cursor.from("_code", true, row("_code", "C-0042", id));
        Cursor back = Cursor.decode(c.encode());
        assertThat(back.column()).isEqualTo("_code");
        assertThat(back.descending()).isTrue();
        assertThat(back.value()).isEqualTo("C-0042");
        assertThat(back.id()).isEqualTo(id);
    }

    @Test
    void roundTripsTypedValues() {
        UUID id = UUID.randomUUID();
        assertThat(Cursor.decode(Cursor.from("n", false, row("n", 1234L, id)).encode()).value())
                .isEqualTo(1234L);
        assertThat(Cursor.decode(Cursor.from("amt", false, row("amt", new BigDecimal("19.95"), id)).encode()).value())
                .isEqualTo(new BigDecimal("19.95"));
        assertThat(Cursor.decode(Cursor.from("flag", false, row("flag", true, id)).encode()).value())
                .isEqualTo(true);
        Timestamp ts = new Timestamp(1_700_000_000_000L);
        assertThat(Cursor.decode(Cursor.from("_date", true, row("_date", ts, id)).encode()).value())
                .isEqualTo(ts);
    }

    @Test
    void roundTripsANullValue() {
        UUID id = UUID.randomUUID();
        Cursor back = Cursor.decode(Cursor.from("note", false, row("note", null, id)).encode());
        assertThat(back.value()).isNull();
        assertThat(back.id()).isEqualTo(id);
    }

    @Test
    void decodeForRejectsAStaleCursor() {
        Cursor c = Cursor.from("_code", true, row("_code", "X", UUID.randomUUID()));
        String token = c.encode();
        // same sort → accepted
        assertThat(Cursor.decodeFor(token, "_code", true)).isNotNull();
        // re-sorted by a different column or direction → treated as absent (start over)
        assertThat(Cursor.decodeFor(token, "_description", true)).isNull();
        assertThat(Cursor.decodeFor(token, "_code", false)).isNull();
    }

    @Test
    void malformedTokensDecodeToNullNotAnException() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode("")).isNull();
        assertThat(Cursor.decode("not-base64-$$$")).isNull();
        assertThat(Cursor.decode("YWJj")).isNull(); // valid base64, wrong shape
    }

    @Test
    void firstPagePlanHasNoSeekPredicate() {
        Keyset.Plan p = Keyset.plan("_code", true, false, null);
        assertThat(p.predicate()).isEmpty();
        assertThat(p.orderBy()).isEqualTo("_code DESC, _id DESC");
        assertThat(p.hasCursor()).isFalse();
    }

    @Test
    void nonNullSeekComparesValueThenId() {
        Cursor c = Cursor.from("_code", false, row("_code", "C-1", UUID.randomUUID()));
        Keyset.Plan p = Keyset.plan("_code", false, false, c);
        assertThat(p.orderBy()).isEqualTo("_code ASC, _id ASC");
        assertThat(p.bindsValue()).isTrue();
        assertThat(p.predicate())
                .contains("_code > :" + Keyset.VALUE_BIND)
                .contains("_id > :" + Keyset.ID_BIND);
    }

    @Test
    void nullSafeSeekBranchesOnTheNullTail() {
        UUID id = UUID.randomUUID();
        // cursor among the non-null rows → still binds the value and includes the null tail
        Keyset.Plan amongValues = Keyset.plan("note", false, true, Cursor.from("note", false, row("note", "a", id)));
        assertThat(amongValues.bindsValue()).isTrue();
        assertThat(amongValues.predicate()).contains("note IS NULL");
        assertThat(amongValues.orderBy()).startsWith("(CASE WHEN note IS NULL");

        // cursor already in the null tail → id-only seek, no value bind
        Keyset.Plan inTail = Keyset.plan("note", false, true, Cursor.from("note", false, row("note", null, id)));
        assertThat(inTail.bindsValue()).isFalse();
        assertThat(inTail.predicate()).isEqualTo(" AND (note IS NULL AND _id > :" + Keyset.ID_BIND + ")");
    }

    @Test
    void clampLimitDefaultsAndCaps() {
        assertThat(Keyset.clampLimit(0)).isEqualTo(Keyset.DEFAULT_LIMIT);
        assertThat(Keyset.clampLimit(-5)).isEqualTo(Keyset.DEFAULT_LIMIT);
        assertThat(Keyset.clampLimit(25)).isEqualTo(25);
        assertThat(Keyset.clampLimit(99999)).isEqualTo(Keyset.MAX_LIMIT);
    }
}
