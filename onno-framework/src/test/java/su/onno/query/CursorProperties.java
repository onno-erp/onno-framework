package su.onno.query;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property-based checks of the v2 keyset cursor codec ({@link Cursor#encodeWindow} /
 * {@link Cursor#decodeWindow}) and the legacy v1 scalar codec.
 *
 * <p>The documented contract (docs/HEADLESS_READ_API.md, AGENTS.md "Collection pagination"):
 * a token round-trips its typed tuple; a malformed position restarts the feed ({@code null});
 * a well-formed token from another feed context is rejected ({@link Cursor.ScopeMismatchException}).
 */
class CursorProperties {

    // ------------------------------------------------------------------ round trips

    @Property(tries = 300)
    @Label("v2: encodeWindow -> decodeWindow round-trips every supported value type")
    void windowRoundTrips(@ForAll("contexts") String context,
                          @ForAll("tuples") List<Object> values) {
        String token = Cursor.encodeWindow(context, values);

        Cursor.Window decoded = Cursor.decodeWindow(token, context, values.size());

        assertThat(decoded).isNotNull();
        assertThat(decoded.values()).hasSize(values.size());
        for (int i = 0; i < values.size(); i++) {
            assertBindsEqual(decoded.values().get(i), values.get(i));
        }
    }

    @Property(tries = 200)
    @Label("v2: a token is URL-safe and never carries a '~' scope seal")
    void windowTokensAreUrlSafe(@ForAll("contexts") String context,
                                @ForAll("tuples") List<Object> values) {
        assertThat(Cursor.encodeWindow(context, values)).matches("[A-Za-z0-9_-]+");
    }

    @Property(tries = 200)
    @Label("v1: Cursor.encode -> decode round-trips the scalar position")
    void scalarRoundTrips(@ForAll("columns") String column, @ForAll boolean descending,
                          @ForAll("scalar") Object value, @ForAll("uuids") UUID id) {
        Cursor decoded = Cursor.decode(new Cursor(column, descending, value, id).encode());

        assertThat(decoded).isNotNull();
        assertThat(decoded.column()).isEqualTo(column);
        assertThat(decoded.descending()).isEqualTo(descending);
        assertThat(decoded.id()).isEqualTo(id);
        assertBindsEqual(decoded.value(), value);
        assertThat(Cursor.decodeFor(decoded.encode(), column, descending)).isNotNull();
        assertThat(Cursor.decodeFor(decoded.encode(), column, !descending)).isNull();
        assertThat(Cursor.decodeFor(decoded.encode(), column + "x", descending)).isNull();
    }

    // ------------------------------------------------------------------ known precision gaps

    /**
     * Minimal counterexample: {@code 10:15:30.123} encodes as {@code "10:15:30"}
     * ({@link java.sql.Time#toString()} prints whole seconds) and decodes to {@code 10:15:30}. The
     * framework maps {@code LocalTime} to {@code TIME}, which is {@code time(6)} on PostgreSQL, so a
     * feed ordered by a time-of-day column with sub-second values re-reads rows of the boundary
     * second (and {@link KeysetWindow#drain} fails on the repeated cursor once a whole window ties).
     * H2's {@code TIME} defaults to whole seconds, so the H2 suites cannot see it.
     */
    @Property(tries = 100)
    @Label("v2: a TIME position keeps its sub-second part")
    @net.jqwik.api.Disabled("bug: TIME cursor values lose fractional seconds (java.sql.Time.toString); a full fix "
            + "also needs KeysetWindow to read TIME columns as LocalTime instead of rs.getObject -> java.sql.Time")
    void timeOfDayKeepsSubSecondPrecision(@ForAll @IntRange(min = 1, max = 86_399_999) int millisOfDay) {
        Assume.that(millisOfDay % 1000 != 0);
        LocalTime time = LocalTime.ofNanoOfDay(millisOfDay * 1_000_000L);

        Object decoded = Cursor.decodeWindow(Cursor.encodeWindow("c", List.of(time)), "c", 1).values().getFirst();

        assertThat(((java.sql.Time) decoded).toLocalTime().toNanoOfDay()
                + Math.floorMod(((java.sql.Time) decoded).getTime(), 1000L) * 1_000_000L)
                .isEqualTo(time.toNanoOfDay());
    }

    /**
     * Minimal counterexample (JVM zone America/New_York): {@code 2026-11-01T05:30:00Z} (01:30 EDT)
     * encodes as the wall-clock {@code 2026-11-01T01:30} and decodes to {@code 06:30:00Z} (01:30 EST),
     * one hour later. An {@code Instant} attribute ({@code TIMESTAMP WITH TIME ZONE}) sorted feed
     * therefore skips (ascending) or repeats (descending) rows in the repeated autumn hour whenever
     * the JVM runs in a zone with daylight saving time. UTC servers are unaffected.
     */
    @Property(tries = 100)
    @Label("v2: an Instant position survives a JVM zone with a daylight-saving fall-back")
    @net.jqwik.api.Disabled("bug: Instant/OffsetDateTime positions are encoded as JVM-zone wall-clock time; "
            + "ambiguous in the DST fall-back hour. Fix needs an offset-carrying tag in the v2 wire format")
    void instantSurvivesDaylightSavingFallBack(@ForAll @IntRange(min = 2000, max = 2100) int year,
                                               @ForAll @IntRange(min = 0, max = 7_199) int secondOfWindow) {
        java.time.ZoneId zone = java.time.ZoneId.of("America/New_York");
        var transition = zone.getRules().nextTransition(LocalDate.of(year, 10, 1).atStartOfDay(zone).toInstant());
        Instant instant = transition.getInstant().minusSeconds(3_600).plusSeconds(secondOfWindow);
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
            Object decoded = Cursor.decodeWindow(Cursor.encodeWindow("c", List.of(instant)), "c", 1)
                    .values().getFirst();
            assertThat(((Timestamp) decoded).toInstant()).isEqualTo(instant);
        } finally {
            java.util.TimeZone.setDefault(previous);
        }
    }

    // ------------------------------------------------------------------ rejection

    @Property(tries = 300)
    @Label("v2: a well-formed token from another context is rejected, never silently reused")
    void foreignContextIsRejected(@ForAll("contexts") String issued, @ForAll("contexts") String active,
                                  @ForAll("tuples") List<Object> values) {
        Assume.that(!issued.equals(active));
        String token = Cursor.encodeWindow(issued, values);

        assertThatThrownBy(() -> Cursor.decodeWindow(token, active, values.size()))
                .isInstanceOf(Cursor.ScopeMismatchException.class);
    }

    @Property(tries = 200)
    @Label("v2: a token decoded with the wrong tuple arity restarts (null)")
    void wrongArityRestarts(@ForAll("contexts") String context, @ForAll("tuples") List<Object> values,
                            @ForAll @IntRange(min = 1, max = 3) int delta) {
        String token = Cursor.encodeWindow(context, values);
        assertThat(Cursor.decodeWindow(token, context, values.size() + delta)).isNull();
    }

    @Property(tries = 500)
    @Label("v2: arbitrary garbage restarts or is rejected as foreign, never another exception")
    void garbageNeverEscapes(@ForAll @Size(max = 200) String garbage, @ForAll("contexts") String context,
                             @ForAll @IntRange(min = 1, max = 4) int size) {
        Throwable thrown = catchThrowable(() -> Cursor.decodeWindow(garbage, context, size));
        assertThat(thrown).satisfiesAnyOf(
                t -> assertThat(t).isNull(),
                t -> assertThat(t).isInstanceOf(Cursor.ScopeMismatchException.class));
    }

    @Property(tries = 500)
    @Label("v2: a tampered token restarts, is rejected, or decodes to a well-typed position")
    void tamperedTokensStayWellTyped(@ForAll("contexts") String context, @ForAll("tuples") List<Object> values,
                                     @ForAll @IntRange(min = 0, max = 10_000) int position,
                                     @ForAll("base64url") char replacement) {
        String token = Cursor.encodeWindow(context, values);
        int at = position % token.length();
        String tampered = token.substring(0, at) + replacement + token.substring(at + 1);

        Cursor.Window decoded;
        try {
            decoded = Cursor.decodeWindow(tampered, context, values.size());
        } catch (Cursor.ScopeMismatchException foreign) {
            return; // a different feed: the transport answers 400
        }
        if (decoded == null) return; // malformed: the feed restarts
        // Still a position: the right arity, every value one of the codec's bindable types.
        assertThat(decoded.values()).hasSize(values.size());
        assertThat(decoded.values()).allSatisfy(v -> assertThat(v == null
                || v instanceof String || v instanceof Long || v instanceof BigDecimal
                || v instanceof Boolean || v instanceof UUID || v instanceof java.sql.Date
                || v instanceof Timestamp || v instanceof java.sql.Time).isTrue());
    }

    @Property(tries = 200)
    @Label("v2: a v1 scalar token handed to a v2 feed is rejected, not reinterpreted")
    void v1TokenOnV2FeedIsRejected(@ForAll("columns") String column, @ForAll("uuids") UUID id,
                                   @ForAll("contexts") String context) {
        String v1 = new Cursor(column, false, 1L, id).encode();
        assertThatThrownBy(() -> Cursor.decodeWindow(v1, context, 1))
                .isInstanceOf(Cursor.ScopeMismatchException.class);
    }

    // ------------------------------------------------------------------ context

    @Property(tries = 200)
    @Label("context(): map entry order does not change the feed identity")
    void contextIgnoresMapInsertionOrder(@ForAll("filterMaps") Map<String, String> filters) {
        Map<String, Object> forward = new java.util.LinkedHashMap<>();
        filters.forEach(forward::put);
        List<String> keys = new ArrayList<>(filters.keySet());
        java.util.Collections.reverse(keys);
        Map<String, Object> reversed = new java.util.LinkedHashMap<>();
        keys.forEach(k -> reversed.put(k, filters.get(k)));

        assertThat(Cursor.context("feed", forward)).isEqualTo(Cursor.context("feed", reversed));
    }

    @Property(tries = 200)
    @Label("context(): distinct filter values give distinct feed identities")
    void contextSeparatesDistinctFilters(@ForAll("contexts") String a, @ForAll("contexts") String b) {
        Assume.that(!a.equals(b));
        assertThat(Cursor.context("feed", Map.of("q", a))).isNotEqualTo(Cursor.context("feed", Map.of("q", b)));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The decoded value must bind to SQL as the original does: same Java value after the codec's
     * documented normalisation (integers widen to Long, LocalDate binds as java.sql.Date,
     * date-times as Timestamp at full precision, LocalTime as java.sql.Time).
     */
    private static void assertBindsEqual(Object decoded, Object original) {
        Object expected = switch (original) {
            case null -> null;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case LocalDate d -> java.sql.Date.valueOf(d);
            case LocalDateTime t -> Timestamp.valueOf(t);
            case Instant i -> Timestamp.from(i);
            case LocalTime t -> java.sql.Time.valueOf(t);
            default -> original;
        };
        if (expected instanceof BigDecimal number) {
            // toString/new BigDecimal is an exact round trip: value AND scale are preserved.
            assertThat(decoded).isInstanceOf(BigDecimal.class);
            assertThat(((BigDecimal) decoded).scale()).isEqualTo(number.scale());
            assertThat((BigDecimal) decoded).isEqualByComparingTo(number);
            return;
        }
        assertThat(decoded).isEqualTo(expected);
        if (expected != null) assertThat(decoded).isInstanceOf(expected.getClass());
    }

    @Provide
    Arbitrary<String> contexts() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMinLength(0).ofMaxLength(40),
                unicode(),
                Arbitraries.strings().withChars('a', 'b', '~', '\u001F', '=', '/').ofMaxLength(8));
    }

    @Provide
    Arbitrary<String> columns() {
        // A v1 column is a validated SQL identifier, never containing the unit separator.
        return Arbitraries.strings().withCharRange('a', 'z').withChars('_', '.').ofMinLength(1).ofMaxLength(20);
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.randomValue(r -> new UUID(r.nextLong(), r.nextLong()));
    }

    @Provide
    Arbitrary<Character> base64url() {
        return Arbitraries.chars().range('A', 'Z').range('a', 'z')
                .range('0', '9').with('-', '_', '~', '=', '!');
    }

    @Provide
    Arbitrary<Map<String, String>> filterMaps() {
        return Arbitraries.maps(Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6), unicode())
                .ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<Object>> tuples() {
        return value().list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<Object> scalar() {
        // The v1 wire form is unit-separator delimited; a v1 string value may not contain U+001F.
        return value().filter(v -> !(v instanceof String s) || s.indexOf('\u001F') < 0);
    }

    /** Every value type a keyset ordering column produces through JDBC (see Cursor#typeTag). */
    static Arbitrary<Object> value() {
        Arbitrary<LocalDateTime> dateTimes = Combinators.combine(
                Arbitraries.longs().between(LocalDate.of(1900, 1, 1).toEpochDay(), LocalDate.of(2200, 12, 31).toEpochDay()),
                Arbitraries.longs().between(0, 86_399_999_999_999L))
                .as((day, nanos) -> LocalDate.ofEpochDay(day).atTime(LocalTime.ofNanoOfDay(nanos)));
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().ofMaxLength(30).map(s -> (Object) s),
                unicode().map(s -> (Object) s),
                Arbitraries.longs().map(l -> (Object) l),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.bigIntegers().between(BigInteger.valueOf(-1_000_000_000_000L).pow(2),
                                BigInteger.valueOf(1_000_000_000_000L).pow(2))
                        .flatMap(unscaled -> Arbitraries.integers().between(-6, 12)
                                .map(scale -> (Object) new BigDecimal(unscaled, scale))),
                Arbitraries.of(true, false).map(b -> (Object) b),
                Arbitraries.randomValue(r -> (Object) new UUID(r.nextLong(), r.nextLong())),
                dateTimes.map(LocalDateTime::toLocalDate).map(d -> (Object) d),
                dateTimes.map(t -> (Object) java.sql.Date.valueOf(t.toLocalDate())),
                dateTimes.map(t -> (Object) t),
                dateTimes.map(t -> (Object) Timestamp.valueOf(t)),
                dateTimes.map(t -> (Object) Timestamp.valueOf(t).toInstant()),
                // TIME columns: the framework schema maps LocalTime to TIME (second precision on H2).
                Arbitraries.integers().between(0, 86_399).map(s -> (Object) LocalTime.ofSecondOfDay(s)));
    }

    /** Valid Unicode text, including astral-plane code points (emoji) and the codec's own delimiters. */
    static Arbitrary<String> unicode() {
        Arbitrary<Integer> codePoints = Arbitraries.oneOf(
                Arbitraries.integers().between(0x20, 0x7E),
                Arbitraries.integers().between(0x400, 0x4FF),      // Cyrillic
                Arbitraries.integers().between(0x4E00, 0x4E80),    // CJK
                Arbitraries.integers().between(0x1F600, 0x1F64F),  // emoji (surrogate pairs in UTF-16)
                Arbitraries.of(0x00, 0x1F, 0x7E, 0x2028, 0xFEFF, 0xFFFD, (int) '~', (int) '='));
        return codePoints.list().ofMaxSize(20).map(cps -> {
            StringBuilder out = new StringBuilder();
            cps.forEach(out::appendCodePoint);
            return out.toString();
        });
    }
}
