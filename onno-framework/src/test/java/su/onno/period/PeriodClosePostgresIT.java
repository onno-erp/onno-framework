package su.onno.period;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;
import su.onno.schema.SqlDialect;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Period close against a real PostgreSQL: the framework-owned tables, inclusive boundary dates,
 * organization scope on a UUID column, hard closes, and concurrent closes of one scope serializing
 * on the row lock. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PeriodClosePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Document(name = "PgPeriodInvoices")
    @PeriodAdjustment
    static class PgInvoice extends DocumentObject {
        @PeriodCloseOrganization
        @Attribute
        private UUID clinic;
    }

    private Jdbi jdbi;
    private PeriodCloseService periods;
    private DocumentDescriptor invoices;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbi.useHandle(h -> h.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
        MetadataRegistry registry = new MetadataRegistry();
        invoices = new MetadataScanner(new DefaultNamingStrategy()).scanDocument(PgInvoice.class);
        registry.registerDocument(invoices);
        jdbi.useHandle(h -> {
            for (String ddl : new SchemaGenerator(registry).generateDDL(SqlDialect.POSTGRESQL)) {
                h.execute(ddl);
            }
        });
        periods = new PeriodCloseService(jdbi, registry);
        periods.ensureSchema();
        periods.ensureSchema(); // idempotent on restart
    }

    @Test
    void boundaryDates_organizationScope_andHardClose() {
        UUID north = UUID.randomUUID();
        UUID south = UUID.randomUUID();
        UUID stored = insert(LocalDateTime.of(2026, 1, 31, 23, 59, 59), north);
        periods.close(PeriodScope.organization(north), LocalDate.of(2026, 1, 31), true, "admin", null);

        assertThatThrownBy(() -> periods.requireDocumentOpen(invoices, stored, null, PeriodOperation.UPDATE))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("2026-01-31");
        assertThatThrownBy(() -> periods.requireDocumentOpen(invoices, null,
                LocalDateTime.of(2026, 1, 31, 0, 0), north, PeriodOperation.CREATE))
                .isInstanceOf(PeriodClosedException.class);
        periods.requireDocumentOpen(invoices, null, LocalDateTime.of(2026, 2, 1, 0, 0), north,
                PeriodOperation.CREATE);
        periods.requireDocumentOpen(invoices, null, LocalDateTime.of(2026, 1, 15, 0, 0), south,
                PeriodOperation.CREATE);

        // Hard: even the adjustment type is refused; reopening at the same date makes it soft.
        periods.reopen(PeriodScope.organization(north), LocalDate.of(2026, 1, 31), "admin", "corrections");
        periods.requireDocumentOpen(invoices, stored, null, PeriodOperation.UPDATE);
        assertThat(periods.audit(10)).extracting(PeriodCloseAuditEntry::action)
                .contains(PeriodCloseAction.CLOSE, PeriodCloseAction.REOPEN, PeriodCloseAction.ADJUSTMENT);
    }

    @Test
    void concurrentCloses_ofOneScope_serializeOnTheRowLock() throws Exception {
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 1), "admin", null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(i);
            results.add(pool.submit(() -> {
                start.await();
                try {
                    periods.close(PeriodScope.global(), date, "admin", null);
                    return true;
                } catch (IllegalArgumentException movedBack) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) succeeded++;
        }
        pool.shutdown();

        assertThat(periods.closedThrough(PeriodScope.global())).contains(LocalDate.of(2026, 1, 1).plusDays(threads));
        List<PeriodCloseAuditEntry> closes = periods.audit(100).stream()
                .filter(e -> e.action() == PeriodCloseAction.CLOSE).toList();
        assertThat(closes).hasSize(succeeded + 1);
        assertThat(closes.stream().filter(e -> e.previous() != null).map(PeriodCloseAuditEntry::previous))
                .doesNotHaveDuplicates();
    }

    private UUID insert(LocalDateTime date, UUID clinic) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + invoices.tableName()
                        + " (_id, _number, _date, _posted, _deletion_mark, clinic)"
                        + " VALUES (:id, 'INV', :date, FALSE, FALSE, :clinic)")
                .bind("id", id).bind("date", date).bind("clinic", clinic).execute());
        return id;
    }
}
