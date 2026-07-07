package su.onno.ui;

import su.onno.metadata.AccumulationRegisterDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.schema.SchemaGenerator;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Regression guard for the Postgres-portability fix in {@link RegisterQueryService}: the
 * period bounds are bound as {@code String} request params, and PostgreSQL refuses to compare
 * a {@code timestamp} column against a {@code varchar} ("operator does not exist: timestamp
 * without time zone &gt;= character varying") unless the bound value is cast — so the SQL wraps
 * the bounds in {@code CAST(:from AS TIMESTAMP)}.
 *
 * <p>This must run on a real PostgreSQL: H2 silently coerces varchar↔timestamp and so never
 * reproduces the failure. The class is skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RegisterQueryServicePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Jdbi jdbi;
    private RegisterQueryService service;
    private AccumulationRegisterDescriptor descriptor;
    private final UUID property = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerAccumulation(scanner.scanRegister(RevenueRegisterFixture.class));
        new SchemaGenerator(registry).execute(jdbi);

        service = new RegisterQueryService(registry, jdbi);
        descriptor = service.require("TestRevenue");

        // The container is shared across tests in the class; start each test from an empty table.
        jdbi.useHandle(h -> h.execute("DELETE FROM " + descriptor.tableName()));

        // One movement dated 2024-06-01.
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _period, _active, property, amount)"
                                + " VALUES (:id, :period, true, :property, :amount)")
                .bind("id", UUID.randomUUID())
                .bind("period", LocalDateTime.of(2024, 6, 1, 10, 0))
                .bind("property", property)
                .bind("amount", new BigDecimal("100.00"))
                .execute());
    }

    @Test
    void turnover_withStringDateBounds_castsAndAggregatesOnPostgres() {
        List<Map<String, Object>> rows = service.turnover(
                descriptor, "1970-01-01T00:00:00", "2999-12-31T23:59:59", Map.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("property", property);
        assertThat((BigDecimal) rows.get(0).get("amount")).isEqualByComparingTo("100.00");
    }

    @Test
    void turnover_boundsCompareAsTimestamps_notLexically() {
        // A window that starts after the movement must exclude it — proving the bound is
        // compared as a timestamp, not as a string.
        List<Map<String, Object>> rows = service.turnover(
                descriptor, "2025-01-01T00:00:00", "2999-12-31T23:59:59", Map.of());

        assertThat(rows).isEmpty();
    }

    @Test
    void movements_withStringDateBounds_returnsRowOnPostgres() {
        List<Map<String, Object>> rows = service.movements(
                descriptor, "2024-01-01T00:00:00", "2024-12-31T23:59:59");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("property", property);
    }

    @Test
    void movementsPage_windowsByOffsetLimit_andCounts() {
        // Two more (newer) movements → 3 total; the page is newest-first by _period.
        insertMovement(LocalDateTime.of(2024, 7, 1, 10, 0), new BigDecimal("200.00"));
        insertMovement(LocalDateTime.of(2024, 8, 1, 10, 0), new BigDecimal("300.00"));

        assertThat(service.movementsCount(descriptor, null, null, null)).isEqualTo(3L);

        // First window of 2, default order (_period DESC) → Aug then Jul.
        List<Map<String, Object>> first = service.movementsPage(descriptor, null, null, null, null, true, 0, 2);
        assertThat(first).hasSize(2);
        assertThat((BigDecimal) first.get(0).get("amount")).isEqualByComparingTo("300.00");
        assertThat((BigDecimal) first.get(1).get("amount")).isEqualByComparingTo("200.00");

        // Second window → only the remaining (oldest) row.
        List<Map<String, Object>> second = service.movementsPage(descriptor, null, null, null, null, true, 2, 2);
        assertThat(second).hasSize(1);
        assertThat((BigDecimal) second.get(0).get("amount")).isEqualByComparingTo("100.00");

        // The count honours the period window (timestamp comparison, not lexical).
        assertThat(service.movementsCount(descriptor, "2024-07-15T00:00:00", "2999-12-31T00:00:00", null))
                .isEqualTo(1L);
    }

    @Test
    void movementsPage_sortsByAValidatedColumn_elseFallsBack() {
        insertMovement(LocalDateTime.of(2024, 7, 1, 10, 0), new BigDecimal("200.00"));
        insertMovement(LocalDateTime.of(2024, 8, 1, 10, 0), new BigDecimal("300.00"));

        // Sort by the resource ascending — proves a client-supplied (validated) sort column is honoured.
        List<Map<String, Object>> rows = service.movementsPage(descriptor, null, null, null, "amount", false, 0, 10);
        assertThat(rows).hasSize(3);
        assertThat((BigDecimal) rows.get(0).get("amount")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) rows.get(2).get("amount")).isEqualByComparingTo("300.00");

        // An unknown sort column falls back to the default (_period DESC) instead of failing.
        List<Map<String, Object>> fallback = service.movementsPage(descriptor, null, null, null, "bogus", true, 0, 10);
        assertThat((BigDecimal) fallback.get(0).get("amount")).isEqualByComparingTo("300.00");
    }

    private void insertMovement(LocalDateTime period, BigDecimal amount) {
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _period, _active, property, amount)"
                                + " VALUES (:id, :period, true, :property, :amount)")
                .bind("id", UUID.randomUUID())
                .bind("period", period)
                .bind("property", property)
                .bind("amount", amount)
                .execute());
    }
}
