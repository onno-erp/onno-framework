package su.onno.repository;

import su.onno.fixtures.TestStockRegister;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.MovementType;
import su.onno.posting.RegisterPersistence;
import su.onno.schema.SchemaGenerator;

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

/**
 * Issue #149: balance reads can narrow to a set of dimension values (IN) or a set of
 * {@code (dimA, dimB)} tuples in a single query, instead of fetching the whole slice and
 * filtering in Java or issuing one query per item.
 */
class RegisterBalanceFilterTest {

    private Jdbi jdbi;
    private RegisterRepositoryImpl<TestStockRegister> repo;

    private final UUID productA = UUID.randomUUID();
    private final UUID productB = UUID.randomUUID();
    private final UUID productC = UUID.randomUUID();
    private final UUID whX = UUID.randomUUID();
    private final UUID whY = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestStockRegister.class);
        registry.registerAccumulation(desc);

        new SchemaGenerator(registry).execute(jdbi);
        repo = new RegisterRepositoryImpl<>(new RegisterPersistence<>(jdbi, desc), TestStockRegister.class);

        movement(productA, whX, "10");
        movement(productB, whX, "7");
        movement(productC, whX, "3");
        movement(productA, whY, "5");   // productA also stocked in a second warehouse
        repo.rebuildTotals();           // populate the totals table the balance read uses
    }

    private void movement(UUID product, UUID warehouse, String qty) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO register_test_stock " +
                                "(_id, _period, _active, _document_ref, _movement_type, product, warehouse, quantity) " +
                                "VALUES (:id, :period, TRUE, :doc, :type, :product, :warehouse, :qty)")
                .bind("id", UUID.randomUUID()).bind("period", LocalDateTime.of(2026, 1, 5, 8, 0))
                .bind("doc", UUID.randomUUID()).bind("type", MovementType.RECEIPT.name())
                .bind("product", product).bind("warehouse", warehouse)
                .bind("qty", new BigDecimal(qty)).execute());
    }

    @Test
    void mapFilter_collectionValue_rendersInList() {
        List<TestStockRegister> rows = repo.getBalance(Map.of("product", List.of(productA, productB)));

        // productA (×2 warehouses) + productB (×1) = 3 rows; productC excluded.
        assertThat(rows).hasSize(3);
        assertThat(rows).allMatch(r -> r.getProduct().equals(productA) || r.getProduct().equals(productB));
    }

    @Test
    void mapFilter_mixedScalarAndCollection_combinesWithAnd() {
        List<TestStockRegister> rows = repo.getBalance(Map.of(
                "warehouse", whX, "product", List.of(productA, productB)));

        // warehouse = whX AND product IN (A, B) -> (A,whX), (B,whX); the (A,whY) row is excluded.
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getWarehouse().equals(whX));
    }

    @Test
    void queryBuilder_whereIn_singleDimension() {
        List<TestStockRegister> rows = repo.query().balance()
                .whereIn(TestStockRegister::getProduct, List.of(productA, productB))
                .execute();

        assertThat(rows).hasSize(3);
        assertThat(rows).noneMatch(r -> r.getProduct().equals(productC));
    }

    @Test
    void queryBuilder_whereAndWhereIn_combine() {
        List<TestStockRegister> rows = repo.query().balance()
                .where(TestStockRegister::getWarehouse, whX)
                .whereIn(TestStockRegister::getProduct, List.of(productA, productB))
                .execute();

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getWarehouse().equals(whX));
    }

    @Test
    void queryBuilder_tupleIn_picksExactDimensionPairs() {
        // The headline: read balances for exactly a document's (product, warehouse) pairs.
        // Both productA and whX are in play, but (productA, whY) must NOT come back.
        List<TestStockRegister> rows = repo.query().balance()
                .whereIn(TestStockRegister::getProduct, TestStockRegister::getWarehouse,
                        List.of(List.of(productA, whX), List.of(productC, whX)))
                .execute();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(productA);
            assertThat(r.getWarehouse()).isEqualTo(whX);
            assertThat(r.getQuantity()).isEqualByComparingTo("10");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(productC);
            assertThat(r.getQuantity()).isEqualByComparingTo("3");
        });
        assertThat(rows).noneMatch(r -> r.getWarehouse().equals(whY));
    }

    @Test
    void queryBuilder_emptyIn_matchesNothing() {
        List<TestStockRegister> rows = repo.query().balance()
                .whereIn(TestStockRegister::getProduct, List.of())
                .execute();

        assertThat(rows).isEmpty();
    }

    @Test
    void registerFilter_whereIn_throughConsumerForm() {
        List<TestStockRegister> rows = repo.getBalance(
                f -> f.whereIn(TestStockRegister::getProduct, List.of(productA, productB)));

        assertThat(rows).hasSize(3);
        assertThat(rows).noneMatch(r -> r.getProduct().equals(productC));
    }
}
