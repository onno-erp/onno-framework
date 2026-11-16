package su.onno.web.comments;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.identity.IdentityLink;
import su.onno.service.IdentityBinding;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommentAuthorAvatars} resolves an author's avatar URL from the identity catalog's
 * photo-named attribute (see {@code IdentityAvatars}: {@code avatarUrl} here), and degrades to
 * nothing when there is no identity link or no stored photo — so the comments panel falls back to
 * initials.
 */
class CommentAuthorAvatarsTest {

    @Catalog(name = "AvatarEmployees")
    static class Employee extends CatalogObject {
        @Attribute(length = 200)
        private String email;
        @Attribute(length = 500)
        private String avatarUrl;
    }

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private CatalogDescriptor empDesc;
    private final UUID withAvatar = UUID.randomUUID();
    private final UUID noAvatar = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:avatars" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Employee.class));
        new SchemaGenerator(registry).execute(jdbi);

        empDesc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Employee.class).findFirst().orElseThrow();
        insert(withAvatar, "E-1", "Ada", "/api/media/2026/06/ada.jpg");
        insert(noAvatar, "E-2", "Grace", null);
    }

    private CommentAuthorAvatars resolver(IdentityBinding identity) {
        return new CommentAuthorAvatars(identity, registry, jdbi);
    }

    private IdentityBinding linkedIdentity() {
        return new IdentityBinding(IdentityLink.of(Employee.class, "email"));
    }

    @Test
    void resolvesTheAvatarFromThePhotoNamedFieldByConvention() {
        // No configuration: the identity catalog's String field named avatarUrl is the photo.
        CommentAuthorAvatars avatars = resolver(linkedIdentity());

        assertThat(avatars.avatarsFor(List.of(withAvatar.toString(), noAvatar.toString())))
                .containsExactly(java.util.Map.entry(withAvatar.toString(), "/api/media/2026/06/ada.jpg"));
    }

    @Test
    void noAvatarWhenThereIsNoIdentityLink() {
        CommentAuthorAvatars avatars = resolver(IdentityBinding.none());

        assertThat(avatars.avatarsFor(List.of(withAvatar.toString()))).isEmpty();
        assertThat(avatars.avatarFor(withAvatar.toString())).isNull();
    }

    private void insert(UUID id, String code, String description, String avatar) {
        String avatarCol = empDesc.attributes().stream()
                .filter(a -> a.fieldName().equals("avatarUrl"))
                .map(AttributeDescriptor::columnName).findFirst().orElseThrow();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + empDesc.tableName()
                        + " (_id, _code, _description, " + avatarCol + ") VALUES (:id, :code, :desc, :avatar)")
                .bind("id", id).bind("code", code).bind("desc", description)
                .bind("avatar", avatar).execute());
    }
}
