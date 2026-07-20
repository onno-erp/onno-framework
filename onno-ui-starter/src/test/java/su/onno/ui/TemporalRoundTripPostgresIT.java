package su.onno.ui;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * PostgreSQL regression for #267: generated/read API timestamp values must round-trip unchanged
 * through catalog, document, and tabular-section writes. H2 does not reproduce PostgreSQL's
 * timestamp representation/coercion differences, so this test is skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class TemporalRoundTripPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Catalog(name = "Pg Episodes", autoNumber = false)
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    static class PgEpisode extends CatalogObject {
        @Attribute private LocalDateTime observedAt;
    }

    @Document(name = "Pg Events", numberPrefix = "PE-")
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    static class PgEvent extends DocumentObject {
        @Attribute private LocalDateTime startsAt;

        @TabularSection(name = "slots")
        private List<PgEventSlot> slots = new ArrayList<>();
    }

    static class PgEventSlot extends TabularSectionRow {
        @Attribute private LocalDateTime happensAt;
    }

    private final Principal admin = new AdminPrincipal();
    private Jdbi jdbi;
    private CatalogDescriptor episodeDesc;
    private DocumentDescriptor eventDesc;
    private CatalogQueryService catalogQuery;
    private DocumentQueryService documentQuery;
    private CatalogCommandService catalogCommands;
    private DocumentCommandService documentCommands;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        episodeDesc = scanner.scan(PgEpisode.class);
        registry.registerCatalog(episodeDesc);
        eventDesc = scanner.scanDocument(PgEvent.class);
        registry.registerDocument(eventDesc);

        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS " + eventDesc.tabularSections().get(0).tableName());
            h.execute("DROP TABLE IF EXISTS " + eventDesc.tableName());
            h.execute("DROP TABLE IF EXISTS " + episodeDesc.tableName());
        });
        new SchemaGenerator(registry).execute(jdbi);

        UiProperties props = new UiProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        UiAccessService access = new UiAccessService(registry);
        ApplicationEventPublisher events = event -> { };
        catalogQuery = new CatalogQueryService(registry, jdbi);
        documentQuery = new DocumentQueryService(registry, jdbi);
        catalogCommands = new CatalogCommandService(registry, jdbi, props, numbers,
                catalogQuery, access, events, cipher);
        documentCommands = new DocumentCommandService(registry, jdbi, props, numbers,
                null, documentQuery, access, events, cipher);
    }

    @Test
    void catalogReadPayload_updatesUnchangedWallClockValue() {
        LocalDateTime expected = LocalDateTime.of(2026, 1, 16, 4, 0);
        UUID id = (UUID) catalogCommands.create(episodeDesc, Map.of(
                "code", "episode-1",
                "observedAt", "2026-01-16T04:00:00.000+00:00"
        ), admin).get("_id");

        Map<String, Object> read = catalogQuery.get(episodeDesc, id);
        assertThat(read.get("observed_at")).isEqualTo("2026-01-16T04:00");

        assertThatCode(() -> catalogCommands.update(episodeDesc, id,
                Map.of("observedAt", read.get("observed_at")), admin)).doesNotThrowAnyException();
        assertThat(rawTimestamp(episodeDesc.tableName(), "observed_at", id)).isEqualTo(expected);
    }

    @Test
    void documentAndTabularReadPayload_updateUnchangedWallClockValues() {
        LocalDateTime header = LocalDateTime.of(2026, 1, 16, 4, 0);
        LocalDateTime line = LocalDateTime.of(2026, 1, 16, 5, 30);
        UUID id = (UUID) documentCommands.create(eventDesc, Map.of(
                "startsAt", "2026-01-16T04:00:00.000+00:00",
                "slots", List.of(Map.of("happensAt", "2026-01-16T05:30:00.000Z"))
        ), admin).get("_id");

        Map<String, Object> read = documentQuery.get(eventDesc, id);
        assertThat(read.get("starts_at")).isEqualTo("2026-01-16T04:00");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) read.get("slots");
        assertThat(rows).singleElement().satisfies(r ->
                assertThat(r.get("happens_at")).isEqualTo("2026-01-16T05:30"));

        Map<String, Object> unchanged = Map.of(
                "startsAt", read.get("starts_at"),
                "slots", List.of(Map.of("happensAt", rows.get(0).get("happens_at")))
        );
        assertThatCode(() -> documentCommands.update(eventDesc, id, unchanged, admin))
                .doesNotThrowAnyException();

        assertThat(rawTimestamp(eventDesc.tableName(), "starts_at", id)).isEqualTo(header);
        String lineTable = eventDesc.tabularSections().get(0).tableName();
        LocalDateTime storedLine = jdbi.withHandle(h ->
                h.createQuery("SELECT happens_at FROM " + lineTable + " WHERE _parent_id = :id")
                        .bind("id", id).mapTo(LocalDateTime.class).one());
        assertThat(storedLine).isEqualTo(line);
    }

    private LocalDateTime rawTimestamp(String table, String column, UUID id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT " + column + " FROM " + table + " WHERE _id = :id")
                        .bind("id", id).mapTo(LocalDateTime.class).one());
    }

    static final class SequentialNumbers implements NumberGenerator {
        private final AtomicInteger counter = new AtomicInteger();
        @Override public String nextNumber(String entityName, int length) {
            return String.format("%0" + Math.max(1, length) + "d", counter.incrementAndGet());
        }
        @Override public String nextCode(String entityName, int length) {
            return String.format("%0" + Math.max(1, length) + "d", counter.incrementAndGet());
        }
    }

    public static final class Authority {
        private final String role;
        public Authority(String role) { this.role = role; }
        public String getAuthority() { return role; }
    }

    public static final class AdminPrincipal implements Principal {
        @Override public String getName() { return "admin"; }
        public List<Authority> getAuthorities() { return List.of(new Authority("ADMIN")); }
    }
}
