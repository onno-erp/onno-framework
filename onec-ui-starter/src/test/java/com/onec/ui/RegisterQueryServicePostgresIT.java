package com.onec.ui;

import com.onec.metadata.AccumulationRegisterDescriptor;
import com.onec.metadata.DefaultNamingStrategy;
import com.onec.metadata.MetadataRegistry;
import com.onec.metadata.MetadataScanner;
import com.onec.schema.SchemaGenerator;

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
}
