package su.onno.service;

import su.onno.access.AccessSubject;
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
 * Verifies a structured {@link Where} predicate is applied server-side by
 * {@link DocumentQueryService} keyset windows and counts on a real PostgreSQL, where a
 * {@code VARCHAR} attribute compared to a string value must bind as text (H2 would coerce silently).
 * Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentWherePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Document(name = "Pg Stays", numberPrefix = "S-")
    public static class PgStay extends DocumentObject {
        @Attribute(length = 20) private String status;
        @Attribute(length = 4) private String season;
    }

    private static final List<String> NONE = List.of();

    private final AccessSubject admin = AccessSubject.user("admin", java.util.Set.of("ADMIN"));
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

        WebProperties props = new WebProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        AccessService access = new AccessService(registry);
        ApplicationEventPublisher events = event -> { };
        documentQuery = new DocumentQueryService(registry, jdbi);
        TestEntityStore.Stack stack = TestEntityStore.builder(new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()), registry).numbers(numbers).cipher(cipher).events(events::publishEvent).build();
        documentCommands = new DocumentCommandService(registry, stack.jdbi(), props, null, documentQuery, access, stack.store());

        stay("CONFIRMED", "2026");
        stay("CHECKED_OUT", "2026");
        stay("DRAFT", "2026");      // inquiry — must be excluded
        stay("CANCELED", "2026");   // cancellation — must be excluded
        stay("CONFIRMED", "2025");  // wrong season — must be excluded by the season clause
    }

    @Test
    void keysetWindow_excludesDraftAndCancelled_andScopesToSeason() {
        String where = "{\"status\":{\"notIn\":[\"DRAFT\",\"CANCELED\"]},\"season\":\"2026\"}";
        List<Map<String, Object>> rows =
                documentQuery.keysetPage(AccessSubject.system(), stayDesc, null, 100, null, false, null, null, null,
                        NONE, NONE, NONE, NONE, NONE, NONE, where).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("status")).isIn("CONFIRMED", "CHECKED_OUT");
            assertThat(r.get("season")).isEqualTo("2026");
        });
    }

    @Test
    void count_matchesTheFilteredWindow() {
        String where = "{\"status\":{\"notIn\":[\"DRAFT\",\"CANCELED\"]},\"season\":\"2026\"}";
        long n = documentQuery.count(AccessSubject.system(), stayDesc, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, where);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void noFilter_returnsEveryLiveRow() {
        long n = documentQuery.count(AccessSubject.system(), stayDesc, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null);
        assertThat(n).isEqualTo(5);
    }

    @Test
    void keysetWindow_appliesWhere() {
        String where = "{\"status\":{\"notIn\":[\"DRAFT\",\"CANCELED\"]},\"season\":\"2026\"}";
        List<Map<String, Object>> rows =
                documentQuery.keysetPage(AccessSubject.system(), stayDesc, null, 100, null, false, null, null, null,
                        NONE, NONE, NONE, NONE, NONE, NONE, where).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("status")).isIn("CONFIRMED", "CHECKED_OUT");
            assertThat(r.get("season")).isEqualTo("2026");
        });
    }

    @Test
    void keysetWindow_noFilter_returnsEveryLiveRow() {
        assertThat(documentQuery.keysetPage(AccessSubject.system(), 
                stayDesc, null, 100, null, false, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, null).rows()).hasSize(5);
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

    /** AccessService reads roles reflectively off getAuthorities().getAuthority(). */
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
