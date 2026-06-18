package su.onno.query;

import su.onno.fixtures.TestStockRegister;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.MovementType;
import su.onno.posting.RegisterPersistence;
import su.onno.repository.RegisterRepositoryImpl;
import su.onno.schema.SchemaGenerator;

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
 * Guards that accumulation-register virtual tables still produce correct balance and
 * turnover results after {@code RegisterPersistence.executeQuery} was refactored onto the
 * shared {@link SqlRenderer} (issue #9 acceptance: no behavior change for registers).
 */
class RegisterQueryRendererTest {

    private Jdbi jdbi;
    private RegisterRepositoryImpl<TestStockRegister> repo;

    private final UUID widget = UUID.randomUUID();
    private final UUID gadget = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();

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

        movement(widget, MovementType.RECEIPT, "10", LocalDateTime.of(2026, 1, 5, 8, 0));
        movement(widget, MovementType.EXPENSE, "3", LocalDateTime.of(2026, 2, 5, 8, 0));
        movement(gadget, MovementType.RECEIPT, "7", LocalDateTime.of(2026, 1, 15, 8, 0));
    }

    private void movement(UUID product, MovementType type, String qty, LocalDateTime period) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO register_test_stock " +
                                "(_id, _period, _active, _document_ref, _movement_type, product, warehouse, quantity) " +
                                "VALUES (:id, :period, TRUE, :doc, :type, :product, :warehouse, :qty)")
                .bind("id", UUID.randomUUID()).bind("period", period).bind("doc", UUID.randomUUID())
                .bind("type", type.name()).bind("product", product).bind("warehouse", warehouse)
                .bind("qty", new BigDecimal(qty)).execute());
    }

    @Test
    void turnover_sumsSignedMovementsAcrossWindow() {
        List<TestStockRegister> rows = repo.query()
                .turnover()
                .from(LocalDateTime.of(2026, 1, 1, 0, 0))
                .to(LocalDateTime.of(2026, 12, 31, 23, 59))
                .execute();

        // widget: +10 -3 = 7 ; gadget: +7
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(widget);
            assertThat(r.getQuantity()).isEqualByComparingTo("7");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(gadget);
            assertThat(r.getQuantity()).isEqualByComparingTo("7");
        });
    }

    @Test
    void pointInTimeBalance_excludesLaterMovements() {
        // As of late January, the widget expense (Feb) has not happened yet.
        List<TestStockRegister> rows = repo.query()
                .balance()
                .at(LocalDateTime.of(2026, 1, 31, 23, 59))
                .execute();

        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getProduct()).isEqualTo(widget);
            assertThat(r.getQuantity()).isEqualByComparingTo("10");
        });
    }

    @Test
    void groupByProduct_collapsesWarehouseDimension() {
        List<TestStockRegister> rows = repo.query()
                .turnover()
                .from(LocalDateTime.of(2026, 1, 1, 0, 0))
                .to(LocalDateTime.of(2026, 12, 31, 23, 59))
                .groupBy(TestStockRegister::getProduct)
                .execute();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.getWarehouse()).isNull());
    }
}
