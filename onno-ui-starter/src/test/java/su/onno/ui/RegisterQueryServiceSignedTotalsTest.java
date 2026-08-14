package su.onno.ui;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
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

class RegisterQueryServiceSignedTotalsTest {

    private Jdbi jdbi;
    private RegisterQueryService service;
    private AccumulationRegisterDescriptor descriptor;
    private AccumulationRegisterDescriptor balanceDescriptor;
    private UUID property;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(dataSource);
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(RevenueRegisterFixture.class));
        registry.registerAccumulation(scanner.scanRegister(BalanceRegisterFixture.class));
        new SchemaGenerator(registry).execute(jdbi);
        service = new RegisterQueryService(registry, jdbi);
        descriptor = service.require("TestRevenue");
        balanceDescriptor = service.require("TestBalance");
        property = UUID.randomUUID();
        insert("RECEIPT", "100.00");
        insert("EXPENSE", "35.00");
    }

    @Test
    void turnoverSubtractsExpenseMovements() {
        Map<String, Object> row = service.turnover(
                        descriptor,
                        "2024-01-01T00:00:00",
                        "2024-12-31T23:59:59",
                        Map.of())
                .getFirst();

        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("65.00");
    }

    @Test
    void widgetTotalSubtractsExpenseMovements() {
        assertThat(service.total(descriptor, "amount", null, null, null))
                .isEqualByComparingTo("65.00");
    }

    @Test
    void boundedMovementsReportsRowsBeyondThePublicCap() {
        jdbi.useHandle(handle -> {
            var batch = handle.prepareBatch(
                    "INSERT INTO " + descriptor.tableName()
                            + " (_id, _period, _active, _document_ref, _movement_type, property, amount)"
                            + " VALUES (:id, :period, TRUE, :document, 'RECEIPT', :property, :amount)");
            for (int i = 0; i < 999; i++) {
                batch.bind("id", UUID.randomUUID())
                        .bind("period", LocalDateTime.of(2024, 6, 2, 10, 0).plusSeconds(i))
                        .bind("document", UUID.randomUUID())
                        .bind("property", property)
                        .bind("amount", BigDecimal.ONE)
                        .add();
            }
            batch.execute();
        });

        RegisterQueryService.BoundedRows result = service.movementsBounded(descriptor, null, null);

        assertThat(result.rows()).hasSize(1000);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void boundedBalanceReportsRowsBeyondThePublicCap() {
        jdbi.useHandle(handle -> {
            var batch = handle.prepareBatch(
                    "INSERT INTO " + balanceDescriptor.totalsTableName()
                            + " (property, amount) VALUES (:property, :amount)");
            for (int i = 0; i < 5001; i++) {
                batch.bind("property", UUID.randomUUID())
                        .bind("amount", BigDecimal.ONE)
                        .add();
            }
            batch.execute();
        });

        RegisterQueryService.BoundedRows result = service.balanceBounded(balanceDescriptor, Map.of());

        assertThat(result.rows()).hasSize(5000);
        assertThat(result.truncated()).isTrue();
    }

    private void insert(String movementType, String amount) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _period, _active, _document_ref, _movement_type, property, amount)"
                                + " VALUES (:id, :period, TRUE, :document, :movementType, :property, :amount)")
                .bind("id", UUID.randomUUID())
                .bind("period", LocalDateTime.of(2024, 6, 1, 10, 0))
                .bind("document", UUID.randomUUID())
                .bind("movementType", movementType)
                .bind("property", property)
                .bind("amount", new BigDecimal(amount))
                .execute());
    }

    @AccumulationRegister(name = "TestBalance", type = AccumulationType.BALANCE)
    static class BalanceRegisterFixture extends AccumulationRecord {
        @Dimension
        private UUID property;

        @Resource
        private BigDecimal amount;
    }
}
