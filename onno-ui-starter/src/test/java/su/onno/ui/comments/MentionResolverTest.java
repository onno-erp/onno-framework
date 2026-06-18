package su.onno.ui.comments;

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
import su.onno.schema.SchemaGenerator;
import su.onno.ui.UiAccessService;
import su.onno.ui.comments.MentionResolver.ResolvedMention;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MentionResolver} resolves a mention's live display + avatar from its target record and gates
 * every mention on the viewer's per-entity read access. Catalogs/documents here carry no
 * {@code @AccessControl}, so they're deny-by-default: only an {@code ADMIN} viewer reads them, which
 * is exactly the lever needed to prove the readable vs. degraded-to-plain-text split.
 */
class MentionResolverTest {

    @Catalog(name = "MnCustomers")
    static class Customer extends CatalogObject {
        @Attribute(length = 500)
        private String avatarUrl;
    }

    @Document(name = "MnInvoices")
    static class Invoice extends DocumentObject {
    }

    private Jdbi jdbi;
    private MentionResolver resolver;
    private CatalogDescriptor customers;
    private DocumentDescriptor invoices;

    private final UUID acme = UUID.randomUUID();
    private final UUID globex = UUID.randomUUID();
    private final UUID inv = UUID.randomUUID();
    private final UUID missing = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mentions" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Customer.class));
        registry.registerDocument(scanner.scanDocument(Invoice.class));
        new SchemaGenerator(registry).execute(jdbi);

        customers = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Customer.class).findFirst().orElseThrow();
        invoices = registry.allDocuments().stream()
                .filter(d -> d.javaClass() == Invoice.class).findFirst().orElseThrow();

        insertCustomer(acme, "C-1", "Acme Corp", "/api/media/acme.png");
        insertCustomer(globex, "C-2", "Globex", null);
        insertInvoice(inv, "INV-42");

        resolver = new MentionResolver(registry, new UiAccessService(registry), jdbi);
    }

    @Test
    void resolvesDisplayAndAvatarForReadableMentions() {
        List<ResolvedMention> out = resolver.resolve(List.of(
                new MentionRef("catalogs", "mncustomers", acme),
                new MentionRef("catalogs", "mncustomers", globex),
                new MentionRef("documents", "mninvoices", inv)), admin());

        assertThat(out).hasSize(3);
        assertThat(out.get(0)).satisfies(m -> {
            assertThat(m.readable()).isTrue();
            assertThat(m.display()).isEqualTo("Acme Corp");
            assertThat(m.avatarUrl()).isEqualTo("/api/media/acme.png");
            assertThat(m.entity()).isEqualTo("MnCustomers");
        });
        // No avatar column value → null avatar, display still resolved.
        assertThat(out.get(1).display()).isEqualTo("Globex");
        assertThat(out.get(1).avatarUrl()).isNull();
        // Documents present by number, no avatar.
        assertThat(out.get(2)).satisfies(m -> {
            assertThat(m.display()).isEqualTo("INV-42");
            assertThat(m.entity()).isEqualTo("MnInvoices");
            assertThat(m.avatarUrl()).isNull();
        });
    }

    @Test
    void degradesMentionsTheViewerCannotRead() {
        // A viewer with no roles can't read a deny-by-default catalog: the mention comes back
        // unreadable with no display leaked, so the client renders plain text instead of a 403 link.
        List<ResolvedMention> out = resolver.resolve(
                List.of(new MentionRef("catalogs", "mncustomers", acme)), nobody());

        assertThat(out).singleElement().satisfies(m -> {
            assertThat(m.readable()).isFalse();
            assertThat(m.display()).isNull();
            assertThat(m.avatarUrl()).isNull();
            assertThat(m.entity()).isNull();
        });
        assertThat(resolver.canRead(nobody(), new MentionRef("catalogs", "mncustomers", acme))).isFalse();
        assertThat(resolver.canRead(admin(), new MentionRef("catalogs", "mncustomers", acme))).isTrue();
    }

    @Test
    void readableEntityButDeletedRecordHasNoDisplay() {
        // The entity is readable, but the record id doesn't exist (deleted/never was): readable stays
        // true (no info leak) but there's no display, so the client falls back to the token's label.
        List<ResolvedMention> out = resolver.resolve(
                List.of(new MentionRef("catalogs", "mncustomers", missing)), admin());

        assertThat(out).singleElement().satisfies(m -> {
            assertThat(m.readable()).isTrue();
            assertThat(m.display()).isNull();
        });
    }

    @Test
    void distinctAndDeterministicOrderEvenWithDuplicates() {
        List<ResolvedMention> out = resolver.resolve(List.of(
                new MentionRef("documents", "mninvoices", inv),
                new MentionRef("catalogs", "mncustomers", acme),
                new MentionRef("documents", "mninvoices", inv)), admin());

        // De-duplicated, first-seen order preserved.
        assertThat(out).hasSize(2);
        assertThat(out.get(0).display()).isEqualTo("INV-42");
        assertThat(out.get(1).display()).isEqualTo("Acme Corp");
    }

    private void insertCustomer(UUID id, String code, String description, String avatar) {
        String avatarCol = customers.attributes().stream()
                .filter(a -> a.fieldName().equals("avatarUrl"))
                .map(AttributeDescriptor::columnName).findFirst().orElseThrow();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + customers.tableName()
                        + " (_id, _code, _description, " + avatarCol + ") VALUES (:id, :code, :desc, :avatar)")
                .bind("id", id).bind("code", code).bind("desc", description).bind("avatar", avatar).execute());
    }

    private void insertInvoice(UUID id, String number) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + invoices.tableName()
                        + " (_id, _number) VALUES (:id, :number)")
                .bind("id", id).bind("number", number).execute());
    }

    private static Principal admin() {
        return new TestPrincipal("root", List.of(new TestAuthority("ADMIN")));
    }

    private static Principal nobody() {
        return new TestPrincipal("guest", List.of());
    }

    /** A principal whose {@code getAuthorities()} the access service reads reflectively. */
    private record TestPrincipal(String name, List<TestAuthority> authorities) implements Principal {
        @Override
        public String getName() {
            return name;
        }

        public List<TestAuthority> getAuthorities() {
            return authorities;
        }
    }

    private record TestAuthority(String authority) {
        public String getAuthority() {
            return authority;
        }
    }
}
