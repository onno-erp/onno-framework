package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogQueryService#groups} — backend grouping for the list grid. Guards that a discrete
 * column groups by value (label + count + subtotal + an {@code eq} expand filter) and that a date
 * column buckets by period via {@code DATE_TRUNC} (H2 syntax) with a {@code ge}/{@code le} expand
 * range, both honouring the same WHERE (filters) as the flat list.
 */
class CatalogQueryServiceGroupsTest {

    @Catalog(name = "GrpOrders")
    static class Order extends CatalogObject {
        @Attribute(length = 60)
        private String tier;
        @Attribute
        private LocalDate placedOn;
        @Attribute(precision = 15, scale = 2)
        private BigDecimal amount;
    }

    private static final List<String> NONE = List.of();

    private Jdbi jdbi;
    private CatalogQueryService service;
    private CatalogDescriptor catalog;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:groups" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Order.class));
        new SchemaGenerator(registry).execute(jdbi);

        service = new CatalogQueryService(registry, jdbi);
        catalog = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Order.class).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsByDiscreteValueWithCountSubtotalAndEqExpand() {
        insert("C-01", "gold", LocalDate.of(2024, 1, 5), new BigDecimal("100.00"));
        insert("C-02", "gold", LocalDate.of(2024, 1, 6), new BigDecimal("50.00"));
        insert("C-03", "std", LocalDate.of(2024, 2, 1), new BigDecimal("10.00"));

        ListGroups.GroupResult res = service.groups(catalog, col("tier"), null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null,
                List.of(new ListGroups.Agg("sum", col("amount"))));

        assertThat(res.capped()).isFalse();
        assertThat(res.groups()).extracting(g -> g.get("label")).containsExactly("gold", "std");
        assertThat(res.groups()).extracting(g -> g.get("count")).containsExactly(2L, 1L);

        Map<String, Object> gold = res.groups().get(0);
        // Subtotal aligned with the requested aggregates (SUM amount).
        assertThat(((List<Object>) gold.get("values")).get(0)).asString().isEqualTo("150.00");
        // Expand filter the client replays on the flat feed to load this group's rows.
        List<Map<String, Object>> expand = (List<Map<String, Object>>) gold.get("expand");
        assertThat(expand).singleElement().isEqualTo(Map.of("op", "eq", "column", col("tier"), "value", "gold"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bucketsADateColumnByMonthWithRangeExpand() {
        insert("C-01", "gold", LocalDate.of(2024, 1, 5), new BigDecimal("1"));
        insert("C-02", "std", LocalDate.of(2024, 1, 20), new BigDecimal("1"));
        insert("C-03", "std", LocalDate.of(2024, 3, 2), new BigDecimal("1"));

        ListGroups.GroupResult res = service.groups(catalog, col("placedOn"), "month", null,
                NONE, NONE, NONE, NONE, NONE, NONE, null, List.of());

        assertThat(res.groups()).extracting(g -> g.get("label")).containsExactly("2024-01", "2024-03");
        assertThat(res.groups()).extracting(g -> g.get("count")).containsExactly(2L, 1L);

        // A date bucket expands with a ge/le range covering the month, not an eq.
        List<Map<String, Object>> expand = (List<Map<String, Object>>) res.groups().get(0).get("expand");
        assertThat(expand).extracting(e -> e.get("op")).containsExactly("ge", "le");
        assertThat(expand).allSatisfy(e -> assertThat(e.get("column")).isEqualTo(col("placedOn")));
        assertThat((String) expand.get(0).get("value")).startsWith("2024-01-01");
        assertThat((String) expand.get(1).get("value")).startsWith("2024-01-31");
    }

    @Test
    void filtersNarrowTheGroupsLikeTheFlatList() {
        insert("C-01", "gold", LocalDate.of(2024, 1, 5), new BigDecimal("1"));
        insert("C-02", "std", LocalDate.of(2024, 1, 6), new BigDecimal("1"));

        // eq tier=gold → only the gold group survives, like the flat list would.
        ListGroups.GroupResult res = service.groups(catalog, col("tier"), null, null,
                List.of(col("tier") + ",gold"), NONE, NONE, NONE, NONE, NONE, null, List.of());

        assertThat(res.groups()).extracting(g -> g.get("label")).containsExactly("gold");
    }

    @Test
    void unknownGroupColumnYieldsNoGroups() {
        insert("C-01", "gold", LocalDate.of(2024, 1, 5), new BigDecimal("1"));
        ListGroups.GroupResult res = service.groups(catalog, "not_a_column", null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null, List.of());
        assertThat(res.groups()).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------

    private String col(String field) {
        return catalog.attributes().stream()
                .filter(a -> a.fieldName().equals(field))
                .map(a -> a.columnName()).findFirst().orElseThrow();
    }

    private void insert(String code, String tier, LocalDate placedOn, BigDecimal amount) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + catalog.tableName()
                        + " (_id, _code, _description, " + col("tier") + ", " + col("placedOn") + ", " + col("amount") + ")"
                        + " VALUES (:id, :code, :code, :tier, :placedOn, :amount)")
                .bind("id", UUID.randomUUID()).bind("code", code)
                .bind("tier", tier).bind("placedOn", placedOn).bind("amount", amount).execute());
    }
}
