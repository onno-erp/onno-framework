package su.onno.service;

import su.onno.access.AccessSubject;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;
import su.onno.annotations.TabularSection;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.repository.EnumerationPersistence;
import su.onno.schema.SchemaGenerator;
import su.onno.validation.ValidationException;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generic write path takes an enum by its constant name ({@code "NEW"}) as well as by its stored
 * UUID — on catalogs, documents and tabular-section rows — and rejects a name that is no constant as
 * a field error (HTTP 400) instead of requiring clients to translate names to UUIDs first.
 */
class EnumNameWriteTest {

    @Enumeration(name = "EnwStatuses")
    public enum Status {
        @EnumLabel("Новый") NEW,
        APPROVED
    }

    @Catalog(name = "EnwTickets")
    @SuppressWarnings({"unused", "FieldMayBeFinal"}) // Reflective model fixture.
    public static class Ticket extends CatalogObject {
        @Attribute private Status status;
        @TabularSection(name = "lines") private List<TicketLine> lines = new ArrayList<>();
    }

    @SuppressWarnings("unused") // Attribute values are populated through reflection.
    public static class TicketLine extends TabularSectionRow {
        @Attribute private Status status;
    }

    @Document(name = "EnwOrders", numberPrefix = "EO-")
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class Order extends DocumentObject {
        @Attribute private Status status;
    }

    private final AccessSubject admin = AccessSubject.user("admin", Set.of("ADMIN"));
    private TestEntityStore.Stack stack;
    private CatalogDescriptor tickets;
    private DocumentDescriptor orders;
    private CatalogCommandService catalogCommands;
    private DocumentCommandService documentCommands;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerEnumeration(scanner.scanEnumeration(Status.class));
        tickets = scanner.scan(Ticket.class);
        registry.registerCatalog(tickets);
        orders = scanner.scanDocument(Order.class);
        registry.registerDocument(orders);
        new SchemaGenerator(registry).execute(jdbi);

        stack = TestEntityStore.builder(ds, registry).build();
        AccessService access = new AccessService(registry);
        catalogCommands = new CatalogCommandService(registry, stack.jdbi(), new WebProperties(),
                new CatalogQueryService(registry, jdbi), access, stack.store());
        // Posting is never reached by create/update.
        documentCommands = new DocumentCommandService(registry, stack.jdbi(), new WebProperties(), null,
                new DocumentQueryService(registry, jdbi), access, stack.store());
    }

    @AfterEach
    void tearDown() {
        stack.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogAndItsLinesAcceptConstantNames() {
        Map<String, Object> created = catalogCommands.create(tickets, Map.of("status", "APPROVED",
                "lines", List.of(Map.of("status", "NEW"))), admin);

        assertThat(stored(created, tickets.attributes())).isEqualTo(id(Status.APPROVED));
        Map<String, Object> line = ((List<Map<String, Object>>) created.get("lines")).getFirst();
        assertThat(stored(line, tickets.tabularSections().getFirst().attributes())).isEqualTo(id(Status.NEW));
    }

    @Test
    void storedUuidsStillWork() {
        Map<String, Object> created = catalogCommands.create(tickets,
                Map.of("status", id(Status.NEW).toString()), admin);
        UUID ticket = (UUID) created.get("_id");

        Map<String, Object> updated = catalogCommands.update(tickets, ticket,
                Map.of("status", id(Status.APPROVED).toString()), admin);

        assertThat(stored(updated, tickets.attributes())).isEqualTo(id(Status.APPROVED));
    }

    @Test
    void documentAcceptsConstantName() {
        Map<String, Object> created = documentCommands.create(orders, Map.of("status", "NEW"), admin);
        UUID order = (UUID) created.get("_id");

        Map<String, Object> updated = documentCommands.update(orders, order, Map.of("status", "APPROVED"), admin);

        assertThat(stored(created, orders.attributes())).isEqualTo(id(Status.NEW));
        assertThat(stored(updated, orders.attributes())).isEqualTo(id(Status.APPROVED));
    }

    @Test
    void unknownNameIsAFieldErrorListingTheConstants() {
        // A removed constant, a different case and the localized label are all not constant names.
        for (String invalid : List.of("REMOVED", "new", "Новый")) {
            assertThatThrownBy(() -> catalogCommands.create(tickets, Map.of("status", invalid), admin))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors())
                            .containsEntry("status", List.of("Status must be one of NEW, APPROVED")));
            assertThatThrownBy(() -> documentCommands.create(orders, Map.of("status", invalid), admin))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors())
                            .containsKey("status"));
        }
        int rows = stack.jdbi().withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + tickets.tableName())
                .mapTo(Integer.class).one());
        assertThat(rows).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownNameInALineIsKeyedByItsRowNotTheHeaderField() {
        // The header's own status is valid; only the second line's is not.
        Map<String, Object> body = Map.of("status", "NEW",
                "lines", List.of(Map.of("status", "NEW"), Map.of("status", "REMOVED")));

        assertThatThrownBy(() -> catalogCommands.create(tickets, body, admin))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors())
                        .containsOnlyKeys("lines[1].status"));
        Map<String, Object> report = catalogCommands.validate(tickets, null, body, admin);
        assertThat((Map<String, Object>) report.get("fieldErrors")).containsOnlyKeys("lines[1].status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateReportsTheUnknownNameWithoutSaving() {
        Map<String, Object> report = catalogCommands.validate(tickets, null, Map.of("status", "REMOVED"), admin);

        assertThat(report.get("valid")).isEqualTo(false);
        assertThat((Map<String, Object>) report.get("fieldErrors")).containsKey("status");
    }

    private static UUID id(Status status) {
        return EnumerationPersistence.resolveId(Status.class, status);
    }

    /** The stored value of the {@code status} column in a column-keyed row. */
    private static UUID stored(Map<String, Object> row, List<AttributeDescriptor> attributes) {
        String column = attributes.stream().filter(a -> a.fieldName().equals("status"))
                .findFirst().orElseThrow().columnName();
        Object value = row.get(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
