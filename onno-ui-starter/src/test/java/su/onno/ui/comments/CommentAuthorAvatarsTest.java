package su.onno.ui.comments;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.schema.SchemaGenerator;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.FieldHintResolver;
import su.onno.ui.UiIdentityLink;
import su.onno.ui.UiLayout;

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
 * avatar-hinted attribute (the {@code .widget("avatar")} field), and degrades to nothing when there
 * is no identity link or no such attribute — so the comments panel falls back to initials.
 */
class CommentAuthorAvatarsTest {

    @Catalog(name = "AvatarEmployees")
    static class Employee extends CatalogObject {
        @Attribute(length = 200)
        private String email;
        @Attribute(length = 500)
        private String avatarUrl;
    }

    /** Marks {@code avatarUrl} as the avatar image, the same way an app's EntityView would. */
    static class EmployeeView implements EntityView {
        @Override
        public Class<?> entity() {
            return Employee.class;
        }

        @Override
        public void fields(EntityConfigBuilder fields) {
            fields.field("avatarUrl").widget("avatar");
        }
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

    private CommentAuthorAvatars resolver(UiLayout layout, EntityView... views) {
        return new CommentAuthorAvatars(layout, registry, new FieldHintResolver(List.of(views)), jdbi);
    }

    private UiLayout linkedLayout() {
        return new UiLayout(List.of(), List.of(), List.of(), new UiIdentityLink(Employee.class, "email"));
    }

    @Test
    void resolvesAvatarForAuthorsThatHaveOne() {
        CommentAuthorAvatars avatars = resolver(linkedLayout(), new EmployeeView());

        Map<String, String> map = avatars.avatarsFor(List.of(withAvatar.toString(), noAvatar.toString()));

        assertThat(map).containsOnlyKeys(withAvatar.toString());
        assertThat(map.get(withAvatar.toString())).isEqualTo("/api/media/2026/06/ada.jpg");
        assertThat(avatars.avatarFor(withAvatar.toString())).isEqualTo("/api/media/2026/06/ada.jpg");
        assertThat(avatars.avatarFor(noAvatar.toString())).isNull();
    }

    @Test
    void noAvatarWhenIdentityCatalogHasNoAvatarHintedField() {
        // The identity link is present, but no view marks an attribute as the avatar.
        CommentAuthorAvatars avatars = resolver(linkedLayout());

        assertThat(avatars.avatarsFor(List.of(withAvatar.toString()))).isEmpty();
    }

    @Test
    void noAvatarWhenThereIsNoIdentityLink() {
        CommentAuthorAvatars avatars = resolver(new UiLayout(List.of()), new EmployeeView());

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
