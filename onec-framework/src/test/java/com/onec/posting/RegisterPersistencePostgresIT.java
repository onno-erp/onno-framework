package com.onec.posting;

import com.onec.fixtures.TestStockRegister;
import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;
import com.onec.model.MovementType;
import com.onec.schema.SchemaGenerator;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the totals insert-or-increment upsert against a real PostgreSQL: the
 * {@code INSERT ... ON CONFLICT ... DO UPDATE} branch of
 * {@code SqlDialect.upsertIncrement} never executes on H2, so only this test proves the
 * SQL is valid and actually accumulates. Schema generation (including the
 * {@code CREATE INDEX IF NOT EXISTS} statements) also runs against Postgres here.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RegisterPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Jdbi jdbi;
    private RegisterPersistence<TestStockRegister> persistence;
    private AccumulationRegisterDescriptor descriptor;

    private final UUID product = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        descriptor = scanner.scanRegister(TestStockRegister.class);
        registry.registerAccumulation(descriptor);
        new SchemaGenerator(registry).execute(jdbi);

        persistence = new RegisterPersistence<>(jdbi, descriptor);
        jdbi.useHandle(h -> h.execute("DELETE FROM " + descriptor.totalsTableName()));
    }

    private TestStockRegister movement(MovementType type, String quantity) {
        TestStockRegister record = new TestStockRegister();
        record.setMovementType(type);
        record.setProduct(product);
        record.setWarehouse(warehouse);
        record.setQuantity(new BigDecimal(quantity));
        return record;
    }

    @Test
    void upsertIncrementInsertsThenAccumulates() {
        jdbi.useHandle(h -> persistence.updateTotals(h, List.of(
                movement(MovementType.RECEIPT, "10"))));
        jdbi.useHandle(h -> persistence.updateTotals(h, List.of(
                movement(MovementType.RECEIPT, "5"),
                movement(MovementType.EXPENSE, "4"))));

        List<Map<String, Object>> totals = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + descriptor.totalsTableName())
                        .mapToMap()
                        .list());

        assertThat(totals).hasSize(1);
        assertThat((BigDecimal) totals.get(0).get("quantity"))
                .isEqualByComparingTo(new BigDecimal("11"));
    }

    @Test
    void schemaGenerationCreatesIndexesOnPostgres() {
        List<String> indexes = jdbi.withHandle(h ->
                h.createQuery("SELECT indexname FROM pg_indexes WHERE tablename = :table")
                        .bind("table", descriptor.tableName())
                        .mapTo(String.class)
                        .list());

        assertThat(indexes).contains(
                "idx_register_test_stock__period",
                "idx_register_test_stock__document_ref",
                "idx_register_test_stock_product",
                "idx_register_test_stock_warehouse");
    }
}
