package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity lookup matches a login case-insensitively.
 *
 * <p>An SSO principal arrives with whatever casing the provider reports — the Telegram connector
 * stamps the @username exactly as its owner set it — while the stored login may have been written
 * lower-cased by a backfill or typed differently by an admin. Comparing those with SQL {@code =}
 * resolved to nobody: the person stayed signed in but lost their record id, and with it their
 * display name, photo, comment authorship and presence identity, falling back to a generated
 * avatar. Everything here is a regression guard for that.</p>
 */
class CurrentUserIdentityCaseTest {

    @Catalog(name = "CaseEmployees")
    static class CaseEmployee extends CatalogObject {
        @Attribute(displayName = "Login", length = 64)
        private String username;
        @Attribute(displayName = "Photo", length = 255)
        private String avatarUrl;
    }

    static class CaseEmployeeView implements EntityView {
        @Override
        public Class<?> entity() {
            return CaseEmployee.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("avatarUrl").widget("avatar");
        }
    }

    private Handle handle;
    private CurrentUserResolver resolver;

    @BeforeEach
    void setUp() {
        Jdbi jdbi = Jdbi.create("jdbc:h2:mem:identity-" + UUID.randomUUID() + ";MODE=PostgreSQL");
        handle = jdbi.open();
        handle.execute("CREATE TABLE catalog_case_employees (_id UUID PRIMARY KEY,"
                + " _description VARCHAR(128), _deletion_mark BOOLEAN DEFAULT FALSE,"
                + " username VARCHAR(64), avatar_url VARCHAR(255))");

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(CaseEmployee.class));
        UiLayout layout = new UiLayout(List.of(), List.of(), List.of(),
                new UiIdentityLink(CaseEmployee.class, "username"), null);
        resolver = new CurrentUserResolver(layout, registry,
                new FieldHintResolver(List.of(new CaseEmployeeView())), jdbi);
    }

    @AfterEach
    void tearDown() {
        handle.close();
    }

    private UUID employee(String description, String username, String avatarUrl) {
        UUID id = UUID.randomUUID();
        handle.createUpdate("INSERT INTO catalog_case_employees"
                        + " (_id, _description, _deletion_mark, username, avatar_url)"
                        + " VALUES (:id, :d, false, :u, :a)")
                .bind("id", id).bind("d", description).bind("u", username).bind("a", avatarUrl)
                .execute();
        return id;
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    @Test
    void resolvesAMixedCasePrincipalAgainstALowerCasedStoredLogin() {
        UUID id = employee("Мишель", "mikedegeofroy", "/api/media/photo.jpeg");

        CurrentUserResolver.CurrentUser me = resolver.resolve(principal("MikeDeGeofroy"));

        assertThat(me.recordId()).isEqualTo(id.toString());
        assertThat(me.displayName()).isEqualTo("Мишель");
        assertThat(me.avatarUrl()).isEqualTo("/api/media/photo.jpeg");
    }

    @Test
    void resolvesALowerCasePrincipalAgainstAMixedCaseStoredLogin() {
        UUID id = employee("Мишель", "MikeDeGeofroy", null);

        CurrentUserResolver.CurrentUser me = resolver.resolve(principal("mikedegeofroy"));

        assertThat(me.recordId()).isEqualTo(id.toString());
    }

    @Test
    void stillResolvesAnExactMatch() {
        UUID id = employee("Мишель", "mike", "/api/media/photo.jpeg");

        CurrentUserResolver.CurrentUser me = resolver.resolve(principal("mike"));

        assertThat(me.recordId()).isEqualTo(id.toString());
        assertThat(me.avatarUrl()).isEqualTo("/api/media/photo.jpeg");
    }

    @Test
    void leavesAnUnmatchedPrincipalUnlinkedRatherThanGuessing() {
        employee("Мишель", "mike", "/api/media/photo.jpeg");

        CurrentUserResolver.CurrentUser me = resolver.resolve(principal("admin"));

        assertThat(me.recordId()).isNull();
        assertThat(me.avatarUrl()).isNull();
        assertThat(me.username()).isEqualTo("admin");
    }

    @Test
    void ignoresDeletionMarkedRows() {
        UUID gone = employee("Ушёл", "mike", "/api/media/photo.jpeg");
        handle.createUpdate("UPDATE catalog_case_employees SET _deletion_mark = true WHERE _id = :id")
                .bind("id", gone).execute();

        CurrentUserResolver.CurrentUser me = resolver.resolve(principal("mike"));

        assertThat(me.recordId()).isNull();
    }

    @Test
    void picksDeterministicallyWhenTwoRowsDifferOnlyByCaseInsteadOfFailingTheRequest() {
        // Two rows differing only in case are a data error. The contract is that the request still
        // succeeds and keeps answering with the SAME row — not which row SQL happens to order first
        // (UUID ordering differs between Java's signed-long compareTo and the database's).
        UUID first = employee("A", "mike", null);
        UUID second = employee("B", "Mike", null);

        String once = resolver.resolve(principal("MIKE")).recordId();
        String twice = resolver.resolve(principal("mIkE")).recordId();

        assertThat(once).isNotNull().isEqualTo(twice);
        assertThat(once).isIn(first.toString(), second.toString());
    }
}
