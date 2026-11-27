package su.onno.service;

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
                        (String) null)
                .getFirst();

        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("65.00");
    }

    @Test
    void aggregateSumsResourcesSignedByMovementType() {
        var request = new AggregateBuckets.Request("sum", "amount", null, null, null, null, null, null, null, null, null);
        Map<String, Object> out = service.aggregate(AccessSubject.system(), descriptor, request);
        @SuppressWarnings("unchecked")
        var buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSize(1);
        assertThat((BigDecimal) buckets.getFirst().get("value")).isEqualByComparingTo("65.00");
    }

    @Test
    void aggregateGroupsByPeriodBucketAndDimensionWithinWhereAndWindow() {
        UUID other = UUID.randomUUID();
        insertAt(other, LocalDateTime.of(2024, 7, 3, 9, 0), "RECEIPT", "40.00");
        insertAt(other, LocalDateTime.of(2024, 7, 9, 9, 0), "RECEIPT", "2.00");

        var byMonth = new AggregateBuckets.Request("sum", "amount", "count", null, "period", "month", null, "{\"property\":\"" + other + "\"}", "period", "2024-06-01T00:00:00", "2024-08-01T00:00:00");
        @SuppressWarnings("unchecked")
        var months = (List<Map<String, Object>>) service.aggregate(AccessSubject.system(), descriptor, byMonth).get("buckets");
        // June zero-filled (the window starts there), July has both of `other`'s movements.
        assertThat(months).extracting(b -> b.get("key")).containsExactly("2024-06-01T00:00", "2024-07-01T00:00");
        assertThat(((Number) months.get(0).get("value")).doubleValue()).isZero();
        assertThat((BigDecimal) months.get(1).get("value")).isEqualByComparingTo("42.00");
        assertThat(((Number) months.get(1).get("value2")).longValue()).isEqualTo(2L);

        var byProperty = new AggregateBuckets.Request("sum", "amount", null, null, "property", null, null, "{\"movementType\":\"RECEIPT\"}", null, null, null);
        @SuppressWarnings("unchecked")
        var properties = (List<Map<String, Object>>) service.aggregate(AccessSubject.system(), descriptor, byProperty).get("buckets");
        assertThat(properties).hasSize(2);
        assertThat(properties).anySatisfy(b -> {
            assertThat(b.get("key")).isEqualTo(property.toString());
            assertThat((BigDecimal) b.get("value")).isEqualByComparingTo("100.00");
        });
    }

    @Test
    void aggregateRejectsAnUnknownWhereField() {
        var request = new AggregateBuckets.Request("count", null, null, null, null, null, null, "{\"nope\":1}", null, null, null);
        assertThatThrownBy(() -> service.aggregate(AccessSubject.system(), descriptor, request))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void whereNarrowsMovementsTurnoverAndBalance() {
        UUID other = UUID.randomUUID();
        insertAt(other, LocalDateTime.of(2024, 6, 5, 9, 0), "RECEIPT", "7.00");
        String onlyOther = "{\"property\":\"" + other + "\"}";

        assertThat(service.movements(AccessSubject.system(), descriptor, null, null, onlyOther))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("property", other));
        assertThat(service.movements(AccessSubject.system(), descriptor, null, null,
                "{\"movementType\":\"EXPENSE\",\"period\":{\"gte\":\"2024-06-01\"}}")).hasSize(1);
        assertThat(service.turnover(AccessSubject.system(), descriptor, "2024-01-01T00:00:00",
                "2024-12-31T23:59:59", "{\"property\":{\"not\":\"" + other + "\"}}"))
                .singleElement().satisfies(r -> assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("65.00"));

        insertBalance(property, "2024-03-01T00:00:00", "RECEIPT", "10", true);
        insertBalance(other, "2024-03-01T00:00:00", "RECEIPT", "90", true);
        // `where` on a resource compares the balance, before and after a cutoff alike.
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor, "{\"amount\":{\"gt\":50}}", "2024-12-31"))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("property", other));
        assertThatThrownBy(() -> service.balance(AccessSubject.system(), balanceDescriptor, "{\"period\":\"2024-01-01\"}", null))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void logicalRowsUseFieldNamesAndRegisterSystemKeys() {
        var rows = RegisterQueryService.logical(descriptor,
                service.movements(AccessSubject.system(), descriptor, null, null, null));
        assertThat(rows.getFirst()).containsKeys("id", "period", "recorder", "movementType", "property", "amount")
                .doesNotContainKeys("_period", "_document_ref", "_movement_type");
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

        RegisterQueryService.BoundedRows result = service.balanceBounded(AccessSubject.system(), balanceDescriptor, null, null);

        assertThat(result.rows()).hasSize(5000);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void historicalPagesFilterAggregatedResourcesAndBindCursorToCutoff() {
        for (int i = 1; i <= 5; i++) {
            UUID key = new UUID(0, i);
            insertBalance(key, "2024-03-31T12:00:00", "RECEIPT", "10", true);
            insertBalance(key, "2024-03-31T13:00:00", "RECEIPT", "10", true);
            insertBalance(key, "2024-04-01T00:00:00", "RECEIPT", "100", true);
        }
        var filters = ListFilter.parse(List.of(), List.of(), List.of(), List.of(),
                List.of("amount,15"), List.of(), service.balanceFilterColumns(balanceDescriptor),
                service.numericFilterColumns(balanceDescriptor));
        var first = service.balancePage(AccessSubject.system(), balanceDescriptor, filters,
                null, false, null, 2, "2024-03-31");
        assertThat(first.rows()).hasSize(2);
        assertThat(first.rows()).allSatisfy(r -> assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("20"));
        var second = service.balancePage(AccessSubject.system(), balanceDescriptor, filters,
                null, false, first.nextCursor(), 2, "2024-03-31");
        var last = service.balancePage(AccessSubject.system(), balanceDescriptor, filters,
                null, false, second.nextCursor(), 2, "2024-03-31");
        assertThat(last.rows()).hasSize(1);
        assertThat(last.nextCursor()).isNull();
        assertThat(last.hasMore()).isFalse();
        assertThat(service.balanceCount(AccessSubject.system(), balanceDescriptor, filters, "2024-03-31")).isEqualTo(5);
        assertThatThrownBy(() -> service.balancePage(AccessSubject.system(), balanceDescriptor, filters,
                null, false, first.nextCursor(), 2, "2024-04-01"))
                .isInstanceOf(ServiceException.class);
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
                new AccessService(registry));
        var subject = AccessSubject.user("customer", java.util.Set.of("CUSTOMER"));
        String cutoff = "2024-03-31T23:59:59";
        var rows = scoped.balance(subject, balanceDescriptor, null, cutoff);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("property", property);
        assertThat((BigDecimal) rows.getFirst().get("amount")).isEqualByComparingTo("140");
        assertThat(scoped.balance(subject, balanceDescriptor, "{\"property\":\"" + other + "\"}", cutoff)).isEmpty();
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor,
                "{\"property\":\"" + property + "\"}", cutoff)).hasSize(1);
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor, null, "2024-01-01T00:00:00")).isEmpty();
    }

    @Test
    void historicalBalanceDateCutoffIncludesTheWholeDay() {
        for (String period : List.of("2024-03-31T00:00:00", "2024-03-31T23:59:59", "2024-04-01T00:00:00")) {
            insertBalance(property, period, "RECEIPT", "10", true);
        }

        var rows = service.balance(AccessSubject.system(), balanceDescriptor, null, "2024-03-31");

        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.getFirst().get("amount")).isEqualByComparingTo("20");
        assertThat(service.balance(AccessSubject.system(), balanceDescriptor, null, " ")).isEmpty();
    }

    @Test
    void historicalBalanceRejectsMalformedOrOffsetCutoffs() {
        for (String bad : List.of("yesterday", "2024-03-31T23:59:59Z", "2024-03-31T23:59:59+03:00", "2024-13-01")) {
            assertThatThrownBy(() -> service.balance(AccessSubject.system(), balanceDescriptor, null, bad))
                    .isInstanceOf(ServiceException.class)
                    .satisfies(e -> assertThat(((ServiceException) e).status().code()).isEqualTo(400));
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

    private void insertAt(UUID dimension, LocalDateTime period, String movementType, String amount) {
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _period, _active, _document_ref, _movement_type, property, amount)"
                                + " VALUES (:id, :period, TRUE, :document, :movementType, :property, :amount)")
                .bind("id", UUID.randomUUID()).bind("period", period).bind("document", UUID.randomUUID())
                .bind("movementType", movementType).bind("property", dimension)
                .bind("amount", new BigDecimal(amount)).execute());
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
    static class BalanceRegisterFixture extends AccumulationRecord {
        @Dimension
        private UUID property;

        @Resource
        private BigDecimal amount;
    }
}
