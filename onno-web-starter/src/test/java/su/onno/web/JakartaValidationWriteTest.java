package su.onno.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.TabularSection;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.TabularSectionRow;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;
import su.onno.service.AccessService;
import su.onno.service.CatalogCommandService;
import su.onno.service.CatalogQueryService;
import su.onno.service.TestEntityStore;
import su.onno.service.WebProperties;
import su.onno.validation.ValidationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jakarta Bean Validation constraints on an entity reach the same places onno's own constraints do:
 * the form's dry-run {@code POST /api/catalogs/{name}/validate} reports them inline per field
 * (rows included), and the generic create/update rejects the write with the same field errors.
 */
class JakartaValidationWriteTest {

    @Catalog(name = "Bv Contacts")
    @AccessControl(readRoles = "ADMIN")
    @SuppressWarnings("unused") // Attribute values are populated through reflection.
    public static class Contact extends CatalogObject {
        @Attribute @NotBlank(message = "Name is mandatory") private String name;
        @Attribute(length = 40) @Size(max = 10, message = "Nickname is too long") private String nickname;
        @Attribute @Email private String email;
        @TabularSection(name = "phones") private List<Phone> phones = new ArrayList<>();
    }

    @SuppressWarnings("unused") // Attribute values are populated through reflection.
    public static class Phone extends TabularSectionRow {
        @Attribute private String number;
        @Attribute @Positive(message = "Priority must be positive") private Integer priority;
    }

    private final AccessSubject admin = AccessSubject.user("admin", Set.of("ADMIN"));
    private CatalogDescriptor contacts;
    private CatalogCommandService commands;
    private GenericCatalogController controller;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        MetadataRegistry registry = new MetadataRegistry();
        contacts = new MetadataScanner(new DefaultNamingStrategy()).scan(Contact.class);
        registry.registerCatalog(contacts);
        TestEntityStore.Stack stack = TestEntityStore.builder(ds, registry)
                .numbers(new WriteLifecycleCommandServiceTest.SequentialNumbers()).cipher(new SecretCipher(null)).events(event -> { }).build();
        new SchemaGenerator(registry).execute(stack.jdbi());
        CatalogQueryService query = new CatalogQueryService(registry, stack.jdbi());
        commands = new CatalogCommandService(registry, stack.jdbi(), new WebProperties(), query,
                new AccessService(registry), stack.store());
        controller = new GenericCatalogController(query, new AccessService(registry), commands,
                new UiMessages(Map.of()), null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateEndpointReportsJakartaViolationsPerField() {
        Map<String, Object> report = controller.validate(contacts.logicalName(), null, Map.of(
                "name", "",
                "nickname", "much-too-long",
                "email", "nope",
                "phones", List.of(Map.of("number", "1", "priority", 0))), admin);

        assertThat(report.get("valid")).isEqualTo(false);
        Map<String, List<String>> fieldErrors = (Map<String, List<String>>) report.get("fieldErrors");
        assertThat(fieldErrors).containsOnlyKeys("name", "nickname", "email", "phones[0].priority");
        // The Jakarta message is the one reported, and only once (onno does not repeat the rule).
        assertThat(fieldErrors.get("name")).containsExactly("Name is mandatory");
        assertThat(fieldErrors.get("nickname")).containsExactly("Nickname is too long");
        assertThat(fieldErrors.get("phones[0].priority")).containsExactly("Priority must be positive");
    }

    @Test
    void createAndUpdateRejectJakartaViolations() {
        assertThatThrownBy(() -> commands.create(contacts, Map.of("name", " "), admin))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fieldErrors())
                        .containsEntry("name", List.of("Name is mandatory")));

        Map<String, Object> created = commands.create(contacts, Map.of("name", "Ada", "email", "ada@example.com"), admin);
        UUID id = UUID.fromString(created.get("_id").toString());

        assertThatThrownBy(() -> commands.update(contacts, id, Map.of("nickname", "much-too-long"), admin))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).fieldErrors()).containsOnlyKeys("nickname"));
        Map<String, Object> report = controller.validate(contacts.logicalName(), id, Map.of("nickname", "Ada"), admin);
        assertThat(report.get("valid")).isEqualTo(true);
    }

    @Test
    void metadataCarriesJakartaRequiredAndLength() {
        var name = contacts.attributes().stream().filter(a -> a.fieldName().equals("name")).findFirst().orElseThrow();
        var nickname = contacts.attributes().stream().filter(a -> a.fieldName().equals("nickname")).findFirst().orElseThrow();
        assertThat(name.required()).isTrue();
        assertThat(nickname.length()).isEqualTo(40);
        assertThat(nickname.constraints().maxLength()).isEqualTo(10);
    }
}
