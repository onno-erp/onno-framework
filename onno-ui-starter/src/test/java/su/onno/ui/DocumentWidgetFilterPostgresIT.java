package su.onno.ui;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the dashboard widget {@code config("filter", …)} predicate ({@link WidgetFilter}) is
 * applied server-side by {@link DocumentQueryService#page} / {@link DocumentQueryService#count} —
 * the path that backs chart/list/calendar widgets (a stat tile already filters via
 * {@link DocumentQueryService#aggregate}).
 *
 * <p>Runs on a real PostgreSQL because the case it guards is Postgres-specific: a {@code VARCHAR}
 * column ({@code season}) compared to a quoted literal ({@code season = '2026'}). An unquoted
 * {@code season = 2026} would bind an int and Postgres would reject the comparison — so this also
 * pins the "quote the season" contract the dashboard relies on. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentWidgetFilterPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Document(name = "Pg Stays", numberPrefix = "S-")
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    public static class PgStay extends DocumentObject {
        @Attribute(length = 20) private String status;
        @Attribute(length = 4) private String season;
    }

    private static final List<String> NONE = List.of();

    private final Principal admin = new AdminPrincipal();
    private Jdbi jdbi;
    private DocumentDescriptor stayDesc;
    private DocumentQueryService documentQuery;
    private DocumentCommandService documentCommands;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        stayDesc = scanner.scanDocument(PgStay.class);
        registry.registerDocument(stayDesc);

        jdbi.useHandle(h -> h.execute("DROP TABLE IF EXISTS " + stayDesc.tableName()));
        new SchemaGenerator(registry).execute(jdbi);

        UiProperties props = new UiProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        UiAccessService access = new UiAccessService(registry);
        ApplicationEventPublisher events = event -> { };
        documentQuery = new DocumentQueryService(registry, jdbi);
        documentCommands = new DocumentCommandService(registry, jdbi, props, numbers,
                null, documentQuery, access, events, cipher);

        stay("CONFIRMED", "2026");
        stay("CHECKED_OUT", "2026");
        stay("DRAFT", "2026");      // inquiry — must be excluded
        stay("CANCELED", "2026");   // cancellation — must be excluded
        stay("CONFIRMED", "2025");  // wrong season — must be excluded by the season clause
    }

    @Test
    void page_excludesDraftAndCancelled_andScopesToSeason() {
        String filter = "status != 'DRAFT' AND status != 'CANCELED' AND season = '2026'";
        List<Map<String, Object>> rows =
                documentQuery.page(stayDesc, 0, 100, null, false, null, null, null,
                        NONE, NONE, NONE, NONE, NONE, NONE, filter);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("status")).isIn("CONFIRMED", "CHECKED_OUT");
            assertThat(r.get("season")).isEqualTo("2026");
        });
    }

    @Test
    void count_matchesTheFilteredPage() {
        String filter = "status != 'DRAFT' AND status != 'CANCELED' AND season = '2026'";
        long n = documentQuery.count(stayDesc, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, filter);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void noFilter_returnsEveryLiveRow() {
        long n = documentQuery.count(stayDesc, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null);
        assertThat(n).isEqualTo(5);
    }

    private void stay(String status, String season) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("season", season);
        documentCommands.create(stayDesc, body, admin);
    }

    /** Deterministic numbering so the IT doesn't need the JDBC sequence table. */
    static final class SequentialNumbers implements NumberGenerator {
        private final AtomicInteger counter = new AtomicInteger();

        @Override public String nextNumber(String entityName, int length) {
            return pad(counter.incrementAndGet(), length);
        }

        @Override public String nextCode(String entityName, int length) {
            return pad(counter.incrementAndGet(), length);
        }

        private static String pad(int value, int length) {
            return String.format("%0" + Math.max(1, length) + "d", value);
        }
    }

    /** UiAccessService reads roles reflectively off getAuthorities().getAuthority(). */
    public static final class Authority {
        private final String role;

        public Authority(String role) {
            this.role = role;
        }

        public String getAuthority() {
            return role;
        }
    }

    public static final class AdminPrincipal implements Principal {
        @Override public String getName() {
            return "admin";
        }

        public List<Authority> getAuthorities() {
            return List.of(new Authority("ADMIN"));
        }
    }
}
