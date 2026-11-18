package su.onno.query;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: draining a keyset feed window by window, for any row set (duplicate sort keys, NULLs,
 * nullable and NOT NULL columns, mixed directions, a SQL visibility predicate) and any page size
 * in 1..500, yields every visible row exactly once and in exactly the order of a full sort with
 * NULLS LAST — the contract {@link KeysetWindow} documents. Runs on H2 like {@code KeysetWindowTest}.
 */
@Tag("property")
class KeysetWindowProperties {

    private static final Jdbi JDBI = newDatabase();
    private static final AtomicInteger TABLES = new AtomicInteger();

    /** One sortable column: its SQL type, whether it is declared NOT NULL, and its value domain. */
    enum Column {
        A("INT"), S("VARCHAR(8)"), D("DATE"), TS("TIMESTAMP(9)");

        final String sqlType;

        Column(String sqlType) {
            this.sqlType = sqlType;
        }

        String sql() {
            return name().toLowerCase();
        }
    }

    record Key(Column column, boolean descending, boolean declaredNotNull) {}

    record Scenario(Map<Column, Boolean> nullable, List<Key> keys, boolean idDescending,
                    boolean idDeclaredNotNull, boolean filtered, List<Map<Column, Object>> rows, int limit) {}

    @Property(tries = 250)
    @Label("drain(windows of any limit) == full NULLS-LAST sort, every visible row exactly once")
    void drainingWindowsEqualsTheFullSort(@ForAll("scenarios") Scenario scenario) {
        String table = "feed_" + TABLES.incrementAndGet();
        try {
            createAndFill(table, scenario);
            List<KeysetWindow.Order> order = new ArrayList<>();
            for (Key key : scenario.keys()) {
                order.add(new KeysetWindow.Order(key.column().sql(), key.descending(), key.declaredNotNull()));
            }
            order.add(new KeysetWindow.Order("id", scenario.idDescending(), scenario.idDeclaredNotNull()));
            String where = scenario.filtered() ? "MOD(id, 4) <> 0" : "1 = 1";

            List<Integer> seen = new ArrayList<>();
            List<String> cursors = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                KeysetWindow.Page<Map<String, Object>> page = KeysetWindow.fetch(JDBI, table, "*", where,
                        q -> {}, order, cursor, scenario.limit(), "feed-" + table);
                assertThat(page.rows().size()).isLessThanOrEqualTo(scenario.limit());
                assertThat(page.hasMore()).isEqualTo(page.nextCursor() != null);
                if (page.hasMore()) assertThat(page.rows()).hasSize(scenario.limit());
                page.rows().forEach(row -> seen.add(((Number) row.get("id")).intValue()));
                cursor = page.nextCursor();
                if (cursor != null) cursors.add(cursor);
                assertThat(++pages).as("feed must terminate").isLessThanOrEqualTo(scenario.rows().size() + 2);
            } while (cursor != null);

            assertThat(seen).containsExactlyElementsOf(expectedOrder(scenario));
            assertThat(new HashSet<>(cursors)).hasSameSizeAs(cursors);
        } finally {
            JDBI.useHandle(h -> h.execute("DROP TABLE IF EXISTS " + table));
        }
    }

    // ------------------------------------------------------------------ oracle

    private static List<Integer> expectedOrder(Scenario scenario) {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= scenario.rows().size(); id++) {
            if (!scenario.filtered() || id % 4 != 0) ids.add(id);
        }
        Comparator<Integer> comparator = (x, y) -> 0;
        for (Key key : scenario.keys()) {
            Comparator<Integer> byKey = (x, y) -> compareNullsLast(
                    scenario.rows().get(x - 1).get(key.column()),
                    scenario.rows().get(y - 1).get(key.column()), key.descending());
            comparator = comparator.thenComparing(byKey);
        }
        Comparator<Integer> byId = Integer::compare;
        comparator = comparator.thenComparing(scenario.idDescending() ? byId.reversed() : byId);
        ids.sort(comparator);
        return ids;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNullsLast(Object x, Object y, boolean descending) {
        if (x == null && y == null) return 0;
        if (x == null) return 1;   // NULLS LAST in both directions
        if (y == null) return -1;
        int natural = ((Comparable) x).compareTo(y);
        return descending ? -natural : natural;
    }

    // ------------------------------------------------------------------ fixture

    private static void createAndFill(String table, Scenario scenario) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE " + table + " (id INT PRIMARY KEY");
        for (Column column : Column.values()) {
            ddl.append(", ").append(column.sql()).append(' ').append(column.sqlType)
                    .append(scenario.nullable().get(column) ? "" : " NOT NULL");
        }
        ddl.append(')');
        JDBI.useHandle(h -> {
            h.execute(ddl.toString());
            var batch = h.prepareBatch("INSERT INTO " + table + " (id, a, s, d, ts) VALUES (:id, :a, :s, :d, :ts)");
            for (int i = 0; i < scenario.rows().size(); i++) {
                Map<Column, Object> row = scenario.rows().get(i);
                batch.bind("id", i + 1)
                        .bindByType("a", row.get(Column.A), Integer.class)
                        .bindByType("s", row.get(Column.S), String.class)
                        .bindByType("d", row.get(Column.D), LocalDate.class)
                        .bindByType("ts", row.get(Column.TS) == null ? null : ((Timestamp) row.get(Column.TS)).toLocalDateTime(),
                                LocalDateTime.class)
                        .add();
            }
            if (!scenario.rows().isEmpty()) batch.execute();
        });
    }

    // ------------------------------------------------------------------ generators

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Map<Column, Boolean>> nullability = Arbitraries.of(true, false).list().ofSize(Column.values().length)
                .map(flags -> {
                    Map<Column, Boolean> map = new java.util.EnumMap<>(Column.class);
                    for (Column c : Column.values()) map.put(c, flags.get(c.ordinal()));
                    return map;
                });
        return nullability.flatMap(nullable -> {
            Arbitrary<List<Key>> keys = Arbitraries.subsetOf(Column.values())
                    .flatMap(set -> Arbitraries.shuffle(new ArrayList<>(set)))
                    .flatMap(columns -> {
                        List<Arbitrary<Key>> each = new ArrayList<>();
                        for (Column column : columns) {
                            // A NOT NULL column may be declared either way; a nullable one never notNull.
                            Arbitrary<Boolean> declared = nullable.get(column) ? Arbitraries.just(false)
                                    : Arbitraries.of(true, false);
                            each.add(Combinators.combine(Arbitraries.of(true, false), declared)
                                    .as((desc, notNull) -> new Key(column, desc, notNull)));
                        }
                        return Combinators.combine(each).as(List::copyOf);
                    });
            Arbitrary<List<Map<Column, Object>>> rows = row(nullable).list().ofMaxSize(60);
            Arbitrary<Integer> limit = Arbitraries.frequencyOf(
                    net.jqwik.api.Tuple.of(6, Arbitraries.integers().between(1, 8)),
                    net.jqwik.api.Tuple.of(3, Arbitraries.integers().between(1, 70)),
                    net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(1, 500)));
            return Combinators.combine(keys, Arbitraries.of(true, false), Arbitraries.of(true, false),
                            Arbitraries.of(true, false), rows, limit)
                    .as((k, idDesc, idNotNull, filtered, r, l) -> new Scenario(nullable, k, idDesc, idNotNull, filtered, r, l));
        });
    }

    /** Small value domains so sort keys collide often; NULL only where the column allows it. */
    private static Arbitrary<Map<Column, Object>> row(Map<Column, Boolean> nullable) {
        Arbitrary<Object> a = orNull(Arbitraries.integers().between(-2, 2).map(i -> (Object) i), nullable.get(Column.A));
        Arbitrary<Object> s = orNull(Arbitraries.of("", "a", "B", "b", "ab", "я", "😀").map(v -> (Object) v),
                nullable.get(Column.S));
        Arbitrary<Object> d = orNull(Arbitraries.of(LocalDate.of(1999, 12, 31), LocalDate.of(2026, 11, 18),
                LocalDate.of(2026, 11, 19)).map(v -> (Object) v), nullable.get(Column.D));
        Arbitrary<Object> ts = orNull(Arbitraries.of(
                Timestamp.valueOf("2026-11-18 10:00:00"),
                Timestamp.valueOf("2026-11-18 10:00:00.000000001"),
                Timestamp.valueOf("2026-11-18 10:00:00.123456789"),
                Timestamp.valueOf("1970-01-01 00:00:00")).map(v -> (Object) v), nullable.get(Column.TS));
        return Combinators.combine(a, s, d, ts).as((av, sv, dv, tsv) -> {
            Map<Column, Object> row = new java.util.EnumMap<>(Column.class);
            row.put(Column.A, av);
            row.put(Column.S, sv);
            row.put(Column.D, dv);
            row.put(Column.TS, tsv);
            return row;
        });
    }

    private static Arbitrary<Object> orNull(Arbitrary<Object> values, boolean nullable) {
        return nullable ? values.injectNull(0.3) : values;
    }

    private static Jdbi newDatabase() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:keyset-properties-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return Jdbi.create(ds);
    }
}
