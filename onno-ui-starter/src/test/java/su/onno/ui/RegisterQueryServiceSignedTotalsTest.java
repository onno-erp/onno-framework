package su.onno.ui;

import su.onno.access.AccessSubject;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Map<String, Object> row = service.turnover(AccessSubject.system(), 
                        descriptor,
                        "2024-01-01T00:00:00",
                        "2024-12-31T23:59:59",
                        Map.of())
                .getFirst();

        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("65.00");
    }

    @Test
    void widgetTotalSubtractsExpenseMovements() {
        assertThat(service.total(AccessSubject.system(), descriptor, "amount", null, null, null))
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

        RegisterQueryService.BoundedRows result = service.movementsBounded(AccessSubject.system(), descriptor, null, null);

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

        RegisterQueryService.BoundedRows result = service.balanceBounded(AccessSubject.system(), balanceDescriptor, Map.of());

        assertThat(result.rows()).hasSize(5000);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void historicalBalanceIncludesBoundaryAndSubtractsExpensesWithFiltersAndScope() {
        for (String period : List.of("2024-03-30T12:00:00", "2024-03-31T23:59:59", "2024-04-01T00:00:00")) {
            insertBalance(property, period, "RECEIPT", "100", true);
            insertBalance(property, period, "EXPENSE", "30", true);
        }
        insertBalance(property, "2024-03-30T12:00:00", "RECEIPT", "999", false);
        UUID other = UUID.randomUUID();
        insertBalance(other, "2024-03-30T12:00:00", "RECEIPT", "500", true);
        var registry = new MetadataRegistry();
        registry.registerAccumulation(balanceDescriptor);
        var policy = su.onno.access.RecordAccessPolicy.forRegister(BalanceRegisterFixture.class)
                .appliesTo("CUSTOMER").read(su.onno.access.RecordScope.eq("property", property));
        var scoped = new RegisterQueryService(registry, jdbi,
                new RecordScopeCompiler(new su.onno.access.RecordPolicies(registry, List.of(policy))),
                new UiAccessService(registry));
        var subject = AccessSubject.user("customer", java.util.Set.of("CUSTOMER"));
        String cutoff = "2024-03-31T23:59:59";
        var rows = scoped.balance(subject, balanceDescriptor, Map.of(), cutoff);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("property", property);
        assertThat((BigDecimal) rows.getFirst().get("amount")).isEqualByComparingTo("140");
        assertThat(scoped.balance(subject, balanceDescriptor, Map.of("property", other.toString()), cutoff)).isEmpty();
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor,
                Map.of("property", property.toString()), cutoff)).hasSize(1);
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor, Map.of(), "2024-01-01T00:00:00")).isEmpty();
    }

    @Test
    void historicalBalanceDateCutoffIncludesTheWholeDay() {
        for (String period : List.of("2024-03-31T00:00:00", "2024-03-31T23:59:59", "2024-04-01T00:00:00")) {
            insertBalance(property, period, "RECEIPT", "10", true);
        }

        var rows = service.balance(AccessSubject.system(), balanceDescriptor, Map.of(), "2024-03-31");

        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.getFirst().get("amount")).isEqualByComparingTo("20");
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor, Map.of(), " ")).isEmpty();
    }

    @Test
    void historicalBalanceRejectsMalformedOrOffsetCutoffs() {
        for (String bad : List.of("yesterday", "2024-03-31T23:59:59Z", "2024-03-31T23:59:59+03:00", "2024-13-01")) {
            assertThatThrownBy(() -> service.balance(AccessSubject.system(), balanceDescriptor, Map.of(), bad))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        }
    }

    private void insertBalance(UUID dimension, String period, String type, String amount, boolean active) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + balanceDescriptor.tableName()
                        + " (_id, _period, _active, _document_ref, _movement_type, property, amount)"
                        + " VALUES (:id, CAST(:period AS TIMESTAMP), :active, :document, :type, :property, :amount)")
                .bind("id", UUID.randomUUID()).bind("document", UUID.randomUUID())
                .bind("period", period).bind("active", active).bind("type", type)
                .bind("property", dimension).bind("amount", new BigDecimal(amount)).execute());
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

    // MetadataScanner reads fixture fields reflectively.
    @SuppressWarnings("unused")
    @AccumulationRegister(name = "TestBalance", type = AccumulationType.BALANCE)
    @su.onno.annotations.AccessControl(readRoles = "CUSTOMER")
    static class BalanceRegisterFixture extends AccumulationRecord {
        @Dimension
        private UUID property;

        @Resource
        private BigDecimal amount;
    }
}
