package su.onno.repository;

import su.onno.fixtures.TestProduct;
import su.onno.fixtures.TestRefSalesRegister;
import su.onno.fixtures.TestRefStockRegister;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.MovementType;
import su.onno.posting.RegisterPersistence;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #207: the typed register reads ({@code getBalance}/{@code getTurnover}) blew up on a
 * {@code Ref<T>} dimension — the row mapper asked JDBC to convert the stored UUID column straight
 * to {@code Ref} ("converting to class su.onno.types.Ref"). The mapper must rebuild the Ref from
 * the UUID against the field's declared target type, the way the write path unwraps it.
 */
class RegisterRefDimensionTest {

    private Jdbi jdbi;
    private RegisterRepositoryImpl<TestRefStockRegister> stock;
    private RegisterRepositoryImpl<TestRefSalesRegister> sales;

    private final UUID productA = UUID.randomUUID();
    private final UUID productB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        AccumulationRegisterDescriptor stockDesc = scanner.scanRegister(TestRefStockRegister.class);
        AccumulationRegisterDescriptor salesDesc = scanner.scanRegister(TestRefSalesRegister.class);
        registry.registerAccumulation(stockDesc);
        registry.registerAccumulation(salesDesc);

        new SchemaGenerator(registry).execute(jdbi);
        stock = new RegisterRepositoryImpl<>(new RegisterPersistence<>(jdbi, stockDesc), TestRefStockRegister.class);
        sales = new RegisterRepositoryImpl<>(new RegisterPersistence<>(jdbi, salesDesc), TestRefSalesRegister.class);
    }

    private void stockMovement(UUID product, String currency, String qty) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO register_test_ref_stock " +
                                "(_id, _period, _active, _document_ref, _movement_type, product, currency, quantity) " +
                                "VALUES (:id, :period, TRUE, :doc, :type, :product, :currency, :qty)")
                .bind("id", UUID.randomUUID()).bind("period", LocalDateTime.of(2026, 1, 5, 8, 0))
                .bind("doc", UUID.randomUUID()).bind("type", MovementType.RECEIPT.name())
                .bind("product", product).bind("currency", currency)
                .bind("qty", new BigDecimal(qty)).execute());
    }

    private void salesMovement(UUID product, String amount) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO register_test_ref_sales " +
                                "(_id, _period, _active, _document_ref, _movement_type, product, amount) " +
                                "VALUES (:id, :period, TRUE, :doc, :type, :product, :amount)")
                .bind("id", UUID.randomUUID()).bind("period", LocalDateTime.of(2026, 1, 5, 8, 0))
                .bind("doc", UUID.randomUUID()).bind("type", MovementType.RECEIPT.name())
                .bind("product", product)
                .bind("amount", new BigDecimal(amount)).execute());
    }

    @Test
    void getBalance_mapsRefDimensionBackToTypedRef() {
        stockMovement(productA, "EUR", "10");
        stockMovement(productB, "USD", "4");
        stock.rebuildTotals();

        List<TestRefStockRegister> rows = stock.getBalance();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(Ref.of(TestProduct.class, productA));
            assertThat(r.getCurrency()).isEqualTo("EUR");
            assertThat(r.getQuantity()).isEqualByComparingTo("10");
        });
        assertThat(rows).allMatch(r -> r.getProduct().type() == TestProduct.class);
    }

    @Test
    void getBalance_filteredByRefValue_stillMaps() {
        stockMovement(productA, "EUR", "10");
        stockMovement(productB, "USD", "4");
        stock.rebuildTotals();

        List<TestRefStockRegister> rows =
                stock.getBalance(f -> f.where(TestRefStockRegister::getProduct, Ref.of(TestProduct.class, productA)));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getProduct().id()).isEqualTo(productA);
    }

    @Test
    void getTurnover_mapsRefDimensionBackToTypedRef() {
        salesMovement(productA, "100");
        salesMovement(productA, "50");
        salesMovement(productB, "7");

        List<TestRefSalesRegister> rows = sales.getTurnover(
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 0, 0));

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(Ref.of(TestProduct.class, productA));
            assertThat(r.getAmount()).isEqualByComparingTo("150");
        });
    }
}
