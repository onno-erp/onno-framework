package su.onno.ui;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.numbering.NumberGenerator;
import su.onno.posting.PostingService;
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

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression guard for #163: a null {@code Ref<T>} (the inherited {@code _parent} self-ref, or any
 * nullable ref attribute) must bind as a typed {@code uuid} NULL so PostgreSQL accepts the insert.
 * JDBI binds an untyped null as {@code character varying}; H2 silently coerces that into a uuid
 * column but PostgreSQL rejects it ("column ... is of type uuid but expression is of type character
 * varying"), so every top-level catalog/document insert (null {@code _parent}) used to 500 on
 * Postgres. Must run on a real PostgreSQL — H2 never reproduces it. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class NullRefBindingPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Catalog(name = "Pg Telegram Users", autoNumber = false, codeLength = 15)
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    public static class PgTelegramUser extends CatalogObject {
        @Attribute(length = 64) private String username;
        @Attribute private Boolean active;
        @Attribute private Ref<PgTelegramUser> manager; // nullable ref attribute
    }

    @Document(name = "Pg Approvals", numberPrefix = "PA-")
    @AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
    public static class PgApproval extends DocumentObject {
        @Attribute(length = 60) private String note;
        @Attribute private Ref<PgTelegramUser> approver; // nullable ref attribute
    }

    private final Principal admin = new AdminPrincipal();
    private Jdbi jdbi;
    private CatalogDescriptor userDesc;
    private DocumentDescriptor approvalDesc;
    private CatalogCommandService catalogCommands;
    private DocumentCommandService documentCommands;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        userDesc = scanner.scan(PgTelegramUser.class);
        registry.registerCatalog(userDesc);
        approvalDesc = scanner.scanDocument(PgApproval.class);
        registry.registerDocument(approvalDesc);

        // Fresh schema per test; the container is shared across the class.
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS " + approvalDesc.tableName());
            h.execute("DROP TABLE IF EXISTS " + userDesc.tableName());
        });
        new SchemaGenerator(registry).execute(jdbi);

        UiProperties props = new UiProperties();
        NumberGenerator numbers = new SequentialNumbers();
        SecretCipher cipher = new SecretCipher(null);
        UiAccessService access = new UiAccessService(registry);
        ApplicationEventPublisher events = event -> { };
        CatalogQueryService catalogQuery = new CatalogQueryService(registry, jdbi);
        DocumentQueryService documentQuery = new DocumentQueryService(registry, jdbi);
        catalogCommands = new CatalogCommandService(registry, jdbi, props, numbers,
                catalogQuery, access, events, cipher);
        documentCommands = new DocumentCommandService(registry, jdbi, props, numbers,
                null, documentQuery, access, events, cipher);
    }

    @Test
    void topLevelCatalog_withNullParentAndNullRef_insertsOnPostgres() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "914438292");
        body.put("description", "Mike");
        body.put("username", "@mikedegeofroy");
        body.put("active", true);

        // Before #163 this threw PSQLException (uuid vs character varying); the call itself is the guard.
        UUID id = (UUID) catalogCommands.create(userDesc, body, admin).get("_id");

        Map<String, Object> row = rawRow(userDesc.tableName(), id);
        assertThat(row.get("_parent")).isNull();
        assertThat(row.get(column(userDesc.attributes(), "manager"))).isNull();
    }

    @Test
    void catalog_withNonNullParentAndRef_stillBindsUuid() {
        Map<String, Object> top = new HashMap<>();
        top.put("code", "boss");
        top.put("description", "Boss");
        UUID bossId = (UUID) catalogCommands.create(userDesc, top, admin).get("_id");

        Map<String, Object> child = new HashMap<>();
        child.put("code", "report");
        child.put("description", "Report");
        child.put("parent", bossId.toString());
        child.put("manager", bossId.toString());
        UUID childId = (UUID) catalogCommands.create(userDesc, child, admin).get("_id");

        Map<String, Object> row = rawRow(userDesc.tableName(), childId);
        assertThat(row.get("_parent")).isEqualTo(bossId);
        assertThat(row.get(column(userDesc.attributes(), "manager"))).isEqualTo(bossId);
    }

    @Test
    void catalogUpdate_clearingRefToNull_bindsTypedNull() {
        Map<String, Object> boss = new HashMap<>();
        boss.put("code", "boss2");
        boss.put("description", "Boss2");
        UUID bossId = (UUID) catalogCommands.create(userDesc, boss, admin).get("_id");

        Map<String, Object> create = new HashMap<>();
        create.put("code", "child2");
        create.put("description", "Child2");
        create.put("manager", bossId.toString());
        UUID childId = (UUID) catalogCommands.create(userDesc, create, admin).get("_id");

        Map<String, Object> clear = new HashMap<>();
        clear.put("manager", null);
        assertThatCode(() -> catalogCommands.update(userDesc, childId, clear, admin))
                .doesNotThrowAnyException();

        Map<String, Object> row = rawRow(userDesc.tableName(), childId);
        assertThat(row.get(column(userDesc.attributes(), "manager"))).isNull();
    }

    @Test
    void document_withNullRefAttribute_insertsOnPostgres() {
        Map<String, Object> body = new HashMap<>();
        body.put("number", "PA-1");
        body.put("note", "no approver yet");

        // Guards two #163 fixes at once: the null `approver` ref binds as a typed uuid null, and the
        // built-in `_date` binds as a real timestamp (not a varchar string) — both required for this
        // insert to succeed on Postgres.
        UUID id = (UUID) documentCommands.create(approvalDesc, body, admin).get("_id");

        Map<String, Object> row = rawRow(approvalDesc.tableName(), id);
        assertThat(row.get(column(approvalDesc.attributes(), "approver"))).isNull();
        assertThat(row.get("_date")).isNotNull(); // the timestamp persisted, proving the _date bind fix
    }

    private Map<String, Object> rawRow(String table, UUID id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + table + " WHERE _id = :id")
                .bind("id", id)
                .mapToMap()
                .one());
    }

    private static String column(List<AttributeDescriptor> attributes, String fieldName) {
        return attributes.stream()
                .filter(a -> a.fieldName().equals(fieldName))
                .map(AttributeDescriptor::columnName)
                .findFirst()
                .orElseThrow();
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
