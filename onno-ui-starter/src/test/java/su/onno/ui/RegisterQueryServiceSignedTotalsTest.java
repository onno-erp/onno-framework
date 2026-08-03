package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterQueryServiceSignedTotalsTest {

    private Jdbi jdbi;
    private RegisterQueryService service;
    private AccumulationRegisterDescriptor descriptor;
    private UUID property;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(dataSource);
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(RevenueRegisterFixture.class));
        new SchemaGenerator(registry).execute(jdbi);
        service = new RegisterQueryService(registry, jdbi);
        descriptor = service.require("TestRevenue");
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
}
