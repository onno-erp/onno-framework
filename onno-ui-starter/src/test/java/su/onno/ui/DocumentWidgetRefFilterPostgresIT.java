package su.onno.ui;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.numbering.NumberGenerator;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.types.Ref;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL regression coverage for UUID-backed Ref widget filters (#346). */
@Testcontainers(disabledWithoutDocker = true)
class DocumentWidgetRefFilterPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Catalog(name = "Pg Databases")
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    public static class PgDatabase extends CatalogObject { }

    @Document(name = "Pg Samples", numberPrefix = "S-")
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    public static class PgSample extends DocumentObject {
        @Attribute private Ref<PgDatabase> database;
        @Attribute private BigDecimal events;
    }

    private static final List<String> NONE = List.of();

    private final Principal admin = new AdminPrincipal();
    private Jdbi jdbi;
    private CatalogDescriptor databaseDesc;
    private DocumentDescriptor sampleDesc;
    private DocumentQueryService documentQuery;
    private DocumentCommandService documentCommands;
    private UUID databaseId;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        databaseDesc = scanner.scan(PgDatabase.class);
        sampleDesc = scanner.scanDocument(PgSample.class);
        registry.registerCatalog(databaseDesc);
        registry.registerDocument(sampleDesc);

        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS " + sampleDesc.tableName());
            h.execute("DROP TABLE IF EXISTS " + databaseDesc.tableName());
        });
        new SchemaGenerator(registry).execute(jdbi);

        documentQuery = new DocumentQueryService(registry, jdbi);
        UiProperties props = new UiProperties();
        UiAccessService access = new UiAccessService(registry);
        ApplicationEventPublisher events = event -> { };
        documentCommands = new DocumentCommandService(registry, jdbi, props, new SequentialNumbers(),
                null, documentQuery, access, events, new SecretCipher(null));

        databaseId = database("Primary");
        UUID otherDatabase = database("Other");
        sample(databaseId, "5");
        sample(databaseId, "7");
        sample(otherDatabase, "100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refFilterBindsUuidAcrossEntityWidgetQueries() {
        String filter = "database = " + databaseId;

        assertThat(documentQuery.aggregate(sampleDesc, "sum", "events", filter))
                .isEqualByComparingTo("12");

        Map<String, Object> aggregate = documentQuery.aggregateBuckets(sampleDesc,
                new WidgetBuckets.Request("sum", "events", null, null,
                        null, null, null, filter, null, null, null));
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) aggregate.get("buckets");
        assertThat(buckets).singleElement().satisfies(bucket ->
                assertThat(new BigDecimal(bucket.get("value").toString())).isEqualByComparingTo("12"));

        assertThat(documentQuery.count(sampleDesc, null, null, null,
                NONE, NONE, NONE, NONE, NONE, NONE, filter)).isEqualTo(2);
        assertThat(documentQuery.keysetPage(sampleDesc, null, 20, null, false,
                null, null, null, NONE, NONE, NONE, NONE, NONE, NONE, filter).rows()).hasSize(2);
    }

    private UUID database(String description) {
        UUID id = UUID.randomUUID();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + databaseDesc.tableName()
                        + " (_id, _code, _description) VALUES (:id, :code, :description)")
                .bind("id", id)
                .bind("code", "DB-" + id.toString().substring(0, 6))
                .bind("description", description)
                .execute());
        return id;
    }

    private void sample(UUID database, String events) {
        Map<String, Object> body = new HashMap<>();
        body.put("database", database.toString());
        body.put("events", new BigDecimal(events));
        documentCommands.create(sampleDesc, body, admin);
    }

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
