package su.onno.query;

import su.onno.fixtures.TestCustomer;
import su.onno.fixtures.TestRegion;
import su.onno.fixtures.TestSalesOrder;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static su.onno.query.Q.*;
import static org.assertj.core.api.Assertions.assertThat;

class QueryEngineTest {

    private Jdbi jdbi;
    private QueryEngine query;

    private final UUID north = UUID.randomUUID();
    private final UUID south = UUID.randomUUID();
    private final UUID acme = UUID.randomUUID();
    private final UUID globex = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(TestRegion.class));
        registry.registerCatalog(scanner.scan(TestCustomer.class));
        registry.registerDocument(scanner.scanDocument(TestSalesOrder.class));

        new SchemaGenerator(registry).execute(jdbi);
        query = new QueryEngine(jdbi, registry);

        seed();
    }

    private void seed() {
        insertRegion(north, "N", "North");
        insertRegion(south, "S", "South");
        insertCustomer(acme, "C-ACME", "Acme", "acme@example.com", north);
        insertCustomer(globex, "C-GLBX", "Globex", "globex@example.com", south);

        insertOrder("SO-1", "APPROVED", "100.00", acme, LocalDateTime.of(2026, 1, 10, 9, 0));
        insertOrder("SO-2", "APPROVED", "250.00", acme, LocalDateTime.of(2026, 2, 5, 9, 0));
        insertOrder("SO-3", "DRAFT", "50.00", globex, LocalDateTime.of(2026, 1, 20, 9, 0));
    }

    private void insertRegion(UUID id, String code, String description) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO catalog_test_regions (_id, _code, _description, _deletion_mark) " +
                                "VALUES (:id, :code, :description, FALSE)")
                .bind("id", id).bind("code", code).bind("description", description).execute());
    }

    private void insertCustomer(UUID id, String code, String description, String email, UUID region) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO catalog_test_customers (_id, _code, _description, _deletion_mark, email, region) " +
                                "VALUES (:id, :code, :description, FALSE, :email, :region)")
                .bind("id", id).bind("code", code).bind("description", description)
                .bind("email", email).bind("region", region).execute());
    }

    private void insertOrder(String number, String status, String amount, UUID customer, LocalDateTime date) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO document_test_sales_orders (_id, _number, _date, _posted, _deletion_mark, customer, status, amount) " +
                                "VALUES (:id, :number, :date, FALSE, FALSE, :customer, :status, :amount)")
                .bind("id", UUID.randomUUID()).bind("number", number).bind("date", date)
                .bind("customer", customer).bind("status", status).bind("amount", new BigDecimal(amount))
                .execute());
    }

    @Test
    void catalogProjection_returnsSelectedColumns() {
        List<Row> rows = query.from(TestCustomer.class)
                .select(col(CatalogObject::getCode), col(TestCustomer::getEmail))
                .orderBy(asc(attr(CatalogObject::getCode)))
                .fetch();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getString("code")).isEqualTo("C-ACME");
        assertThat(rows.get(0).getString("email")).isEqualTo("acme@example.com");
        assertThat(rows.get(0).columns()).noneMatch(c -> c.equalsIgnoreCase("_description"));
    }

    @Test
    void singleHopRefJoin_resolvesDisplayThroughRef() {
        List<Row> rows = query.from(TestSalesOrder.class)
                .select(col(DocumentObject::getNumber),
                        as(ref(TestSalesOrder::getCustomer, CatalogObject::getDescription), "customerName"))
                .orderBy(asc(attr(DocumentObject::getNumber)))
                .fetch();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getString("number")).isEqualTo("SO-1");
        assertThat(rows.get(0).getString("customerName")).isEqualTo("Acme");
        assertThat(rows.get(2).getString("customerName")).isEqualTo("Globex");
    }

    @Test
    void deepRefJoin_followsTwoRefHops() {
        List<Row> rows = query.from(TestSalesOrder.class)
                .select(col(DocumentObject::getNumber),
                        as(ref(TestSalesOrder::getCustomer, TestCustomer::getRegion, CatalogObject::getDescription),
                                "regionName"))
                .orderBy(asc(attr(DocumentObject::getNumber)))
                .fetch();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getString("regionName")).isEqualTo("North");  // SO-1 -> Acme -> North
        assertThat(rows.get(2).getString("regionName")).isEqualTo("South");  // SO-3 -> Globex -> South
    }

    @Test
    void deepAndShallowRefShareOneJoin() {
        QueryEngine.Compiled compiled = query.compile(query.from(TestSalesOrder.class)
                .select(ref(TestSalesOrder::getCustomer, CatalogObject::getDescription),
                        ref(TestSalesOrder::getCustomer, TestCustomer::getRegion, CatalogObject::getDescription))
                .toSpec());

        // The customer hop is emitted once and reused by the deeper region hop.
        int customerJoins = compiled.sql().split("LEFT JOIN catalog_test_customers", -1).length - 1;
        assertThat(customerJoins).isEqualTo(1);
        assertThat(compiled.sql()).contains("LEFT JOIN catalog_test_regions");
    }

    @Test
    void filterGroupOrder_aggregatesPerStatus() {
        List<Row> rows = query.from(TestSalesOrder.class)
                .select(col(TestSalesOrder::getStatus), count(), sum(TestSalesOrder::getAmount))
                .where(gte(TestSalesOrder::getAmount, new BigDecimal("60.00")))
                .groupBy(attr(TestSalesOrder::getStatus))
                .orderBy(asc(attr(TestSalesOrder::getStatus)))
                .fetch();

        // DRAFT order (50.00) filtered out; only the two APPROVED orders remain.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString("status")).isEqualTo("APPROVED");
        assertThat(rows.get(0).getLong("count")).isEqualTo(2);
        assertThat(rows.get(0).getBigDecimal("sum_amount")).isEqualByComparingTo("350.00");
    }

    @Test
    void refPredicate_bindsByUnwrappedUuid() {
        List<Row> rows = query.from(TestSalesOrder.class)
                .select(col(DocumentObject::getNumber))
                .where(eq(TestSalesOrder::getCustomer, Ref.of(TestCustomer.class, acme)))
                .orderBy(asc(attr(DocumentObject::getNumber)))
                .fetch();

        assertThat(rows).extracting(r -> r.getString("number"))
                .containsExactly("SO-1", "SO-2");
    }

    @Test
    void fetchInto_mapsRowsToRecord() {
        record OrderLine(String number, String customerName, BigDecimal amount) {
        }

        List<OrderLine> lines = query.from(TestSalesOrder.class)
                .select(col(DocumentObject::getNumber),
                        as(ref(TestSalesOrder::getCustomer, CatalogObject::getDescription), "customerName"),
                        col(TestSalesOrder::getAmount))
                .where(eq(TestSalesOrder::getStatus, "APPROVED"))
                .orderBy(asc(attr(DocumentObject::getNumber)))
                .fetchInto(OrderLine.class);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).number()).isEqualTo("SO-1");
        assertThat(lines.get(0).customerName()).isEqualTo("Acme");
        assertThat(lines.get(0).amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void specIsExpressibleWithoutFluentApi() {
        QuerySpec spec = new QuerySpec(
                TestSalesOrder.class,
                List.of(new Select(Path.of(TestSalesOrder.class, "number"), Select.Agg.NONE, null)),
                List.of(new Predicate(Path.of(TestSalesOrder.class, "status"),
                        Predicate.Op.EQ, "DRAFT", null, null)),
                List.of(),
                List.of(),
                List.of(),
                null, null);

        List<Row> rows = query.fetch(spec);
        assertThat(rows).extracting(r -> r.getString("number")).containsExactly("SO-3");
    }
}
