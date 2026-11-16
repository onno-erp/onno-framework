package su.onno.spring;

import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.numbering.NumberGenerator;
import su.onno.period.PeriodCloseService;
import su.onno.period.PeriodClosedException;
import su.onno.period.PeriodOverride;
import su.onno.period.PeriodScope;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.spring.fixtures.TestStarterInvoice;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The repository save path ({@link OnnoBeforeConvertCallback}) refuses to create, change or
 * delete-mark a document inside a closed period.
 */
class PeriodCloseSaveCallbackTest {

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private PeriodCloseService periods;
    private OnnoBeforeConvertCallback callback;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        registry = new MetadataRegistry();
        registry.registerDocument(new MetadataScanner(new DefaultNamingStrategy())
                .scanDocument(TestStarterInvoice.class));
        new SchemaGenerator(registry).execute(jdbi);

        periods = new PeriodCloseService(jdbi, registry);
        periods.ensureSchema();
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 31), "admin", null);

        callback = new OnnoBeforeConvertCallback(registry, numbers(), new SecretCipher("test-secret-key"));
        callback.setPeriodCloseService(periods);
    }

    @Test
    void newDocumentInsideTheClosedPeriod_isRejected() {
        assertThatThrownBy(() -> callback.onBeforeConvert(invoice(LocalDateTime.of(2026, 1, 31, 12, 0))))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot create");

        callback.onBeforeConvert(invoice(LocalDateTime.of(2026, 2, 1, 0, 0)));
    }

    @Test
    void storedDocumentInsideTheClosedPeriod_cannotBeChangedMovedOrDeleted() {
        TestStarterInvoice stored = stored(LocalDateTime.of(2026, 1, 10, 9, 0));

        assertThatThrownBy(() -> callback.onBeforeConvert(stored))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot change");

        stored.setDate(LocalDateTime.of(2026, 3, 1, 9, 0));
        assertThatThrownBy(() -> callback.onBeforeConvert(stored)).isInstanceOf(PeriodClosedException.class);

        stored.setDeletionMark(true);
        assertThatThrownBy(() -> callback.onBeforeConvert(stored))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot delete");

        periods.override(new PeriodOverride("chief", "correction"), () -> callback.onBeforeConvert(stored));
    }

    @Test
    void openDocument_cannotBeMovedIntoTheClosedPeriod() {
        TestStarterInvoice stored = stored(LocalDateTime.of(2026, 2, 10, 9, 0));
        callback.onBeforeConvert(stored);

        stored.setDate(LocalDateTime.of(2026, 1, 20, 9, 0));
        assertThatThrownBy(() -> callback.onBeforeConvert(stored)).isInstanceOf(PeriodClosedException.class);
    }

    private TestStarterInvoice invoice(LocalDateTime date) {
        TestStarterInvoice invoice = new TestStarterInvoice();
        invoice.setNumber("INV");
        invoice.setDate(date);
        return invoice;
    }

    private TestStarterInvoice stored(LocalDateTime date) {
        TestStarterInvoice invoice = invoice(date);
        invoice.setId(UUID.randomUUID());
        invoice.setNew(false);
        String table = registry.getDocumentDescriptor(TestStarterInvoice.class).tableName();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + table
                        + " (_id, _number, _date, _posted, _deletion_mark) VALUES (:id, 'INV', :date, FALSE, FALSE)")
                .bind("id", invoice.getId()).bind("date", date).execute());
        return invoice;
    }

    private static NumberGenerator numbers() {
        return new NumberGenerator() {
            @Override
            public String nextNumber(String entityName, int length) {
                return "0";
            }

            @Override
            public String nextCode(String entityName, int length) {
                return "0";
            }
        };
    }
}
