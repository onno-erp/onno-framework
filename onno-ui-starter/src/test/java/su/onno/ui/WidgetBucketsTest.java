package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chart widget's grouped-aggregate read (#199): a server-side {@code GROUP BY} returning
 * O(buckets) rows — discrete grouping, DATE_TRUNC bucketing with a time window and span, series
 * split, Ref label resolution, and the injection guards.
 */
class WidgetBucketsTest {

    @Catalog(name = "BucketCustomers")
    static class Customer extends CatalogObject {
    }

    @Catalog(name = "BucketOrders")
    static class Order extends CatalogObject {
        @Attribute(length = 30)
        private String status;
        @Attribute
        private Ref<Customer> customer;
        @Attribute
        private LocalDateTime placedAt;
        @Attribute(precision = 15, scale = 2)
        private BigDecimal amount;
    }

    private Jdbi jdbi;
    private CatalogQueryService service;
    private CatalogDescriptor orders;
    private CatalogDescriptor customers;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:buckets" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Customer.class));
        registry.registerCatalog(scanner.scan(Order.class));
        new SchemaGenerator(registry).execute(jdbi);

        service = new CatalogQueryService(registry, jdbi);
        orders = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Order.class).findFirst().orElseThrow();
        customers = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Customer.class).findFirst().orElseThrow();
    }

    private static WidgetBuckets.Request request(String metric, String field, String groupBy,
                                                 String groupByDate, String seriesBy, String filter,
                                                 String dateField, String from, String to) {
        return new WidgetBuckets.Request(metric, field, null, null,
                groupBy, groupByDate, seriesBy, filter, dateField, from, to);
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsByDiscreteColumn_countPerBucket() {
        insert("new", null, LocalDateTime.of(2026, 1, 5, 10, 0), "10");
        insert("new", null, LocalDateTime.of(2026, 1, 6, 10, 0), "20");
        insert("done", null, LocalDateTime.of(2026, 1, 7, 10, 0), "5");

        Map<String, Object> out = service.aggregateBuckets(orders,
                request("count", null, col("status"), null, null, null, null, null, null));

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(2);
        assertThat(buckets).extracting(b -> b.get("key")).containsExactlyInAnyOrder("new", "done");
        assertThat(buckets).extracting(b -> ((Number) b.get("value")).longValue())
                .containsExactlyInAnyOrder(2L, 1L);
        assertThat((Boolean) out.get("truncated")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dateBucketsWithWindowAndSpan_sumPerBucket() {
        insert("new", null, LocalDateTime.of(2026, 1, 5, 9, 0), "10");
        insert("new", null, LocalDateTime.of(2026, 1, 5, 15, 0), "20");
        insert("new", null, LocalDateTime.of(2026, 2, 10, 9, 0), "40");
        insert("new", null, LocalDateTime.of(2025, 6, 1, 9, 0), "999"); // outside the window

        Map<String, Object> out = service.aggregateBuckets(orders,
                request("sum", col("amount"), col("placedAt"), "day", null, null,
                        col("placedAt"), "2026-01-01T00:00:00", "2026-12-31T00:00:00"));

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        // Chronological (ORDER BY bucket), one row per day, out-of-window row excluded.
        assertThat(buckets).hasSize(2);
        assertThat((String) buckets.get(0).get("key")).startsWith("2026-01-05");
        assertThat(new BigDecimal(buckets.get(0).get("value").toString())).isEqualByComparingTo("30");
        assertThat((String) buckets.get(1).get("key")).startsWith("2026-02-10");

        // Span covers the windowed rows only — what the client sizes granularity from.
        Map<String, Object> span = (Map<String, Object>) out.get("span");
        assertThat((String) span.get("min")).startsWith("2026-01-05");
        assertThat((String) span.get("max")).startsWith("2026-02-10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void weekTruncation_worksOnH2() {
        insert("new", null, LocalDateTime.of(2026, 1, 6, 9, 0), "1");   // Tue of ISO week 2
        insert("new", null, LocalDateTime.of(2026, 1, 8, 9, 0), "1");   // Thu of the same week

        Map<String, Object> out = service.aggregateBuckets(orders,
                request("count", null, col("placedAt"), "week", null, null, null, null, null));

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(1);
        assertThat((String) buckets.get(0).get("key")).startsWith("2026-01-05"); // the week's Monday
    }

    @Test
    @SuppressWarnings("unchecked")
    void seriesBySplitsBuckets_andWidgetFilterApplies() {
        insert("new", null, LocalDateTime.of(2026, 1, 5, 9, 0), "10");
        insert("done", null, LocalDateTime.of(2026, 1, 5, 15, 0), "20");
        insert("canceled", null, LocalDateTime.of(2026, 1, 5, 16, 0), "99");

        Map<String, Object> out = service.aggregateBuckets(orders,
                request("sum", col("amount"), col("placedAt"), "day", col("status"),
                        col("status") + " != canceled", null, null, null));

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(2); // one day × two surviving statuses
        assertThat(buckets).extracting(b -> b.get("series")).containsExactlyInAnyOrder("new", "done");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refGroupBy_resolvesDisplayLabel() {
        UUID customer = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + customers.tableName()
                        + " (_id, _code, _description) VALUES (:id, 'C-01', 'ACME Corp')")
                .bind("id", customer).execute());
        insert("new", customer, LocalDateTime.of(2026, 1, 5, 9, 0), "10");

        Map<String, Object> out = service.aggregateBuckets(orders,
                request("count", null, col("customer"), null, null, null, null, null, null));

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).singleElement().satisfies(b -> {
            assertThat(b.get("key")).isEqualTo(customer.toString());
            assertThat(b.get("label")).isEqualTo("ACME Corp");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankGroupBy_yieldsOneGrandTotalBucket() {
        insert("new", null, LocalDateTime.of(2026, 1, 5, 9, 0), "10");
        insert("done", null, LocalDateTime.of(2026, 1, 6, 9, 0), "5");

        Map<String, Object> out = service.aggregateBuckets(orders,
                request("sum", col("amount"), null, null, null, null, null, null, null));

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).singleElement().satisfies(b -> {
            assertThat(b).doesNotContainKey("key");
            assertThat(new BigDecimal(b.get("value").toString())).isEqualByComparingTo("15");
        });
    }

    @Test
    void unknownColumnsAndUnitsAreRejected() {
        assertThatThrownBy(() -> service.aggregateBuckets(orders,
                request("count", null, "no_such_col", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregateBuckets(orders,
                request("count", null, "x; DROP TABLE t", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregateBuckets(orders,
                request("count", null, col("placedAt"), "decade", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregateBuckets(orders,
                request("sum", "no_such_col", col("status"), null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---------------------------------------------------------------

    private String col(String field) {
        return orders.attributes().stream()
                .filter(a -> a.fieldName().equals(field))
                .map(a -> a.columnName()).findFirst().orElseThrow();
    }

    private void insert(String status, UUID customer, LocalDateTime placedAt, String amount) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + orders.tableName()
                        + " (_id, _code, _description, " + col("status") + ", " + col("customer") + ", "
                        + col("placedAt") + ", " + col("amount") + ")"
                        + " VALUES (:id, :code, :code, :status, :customer, :placedAt, :amount)")
                .bind("id", UUID.randomUUID()).bind("code", "O-" + UUID.randomUUID().toString().substring(0, 6))
                .bind("status", status).bind("customer", customer)
                .bind("placedAt", placedAt).bind("amount", new BigDecimal(amount)).execute());
    }
}
