package su.onno.importer;

import su.onno.access.AccessSubject;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.repository.EnumerationPersistence;
import su.onno.schema.SchemaGenerator;
import su.onno.service.AccessService;
import su.onno.service.CatalogCommandService;
import su.onno.service.CatalogQueryService;
import su.onno.service.TestEntityStore;
import su.onno.service.WebProperties;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CSV import takes an enum column as the constant name or the stored UUID, through the real generic
 * write path; a value that is no constant (here the localized label) fails only its own row.
 */
class CsvEnumImportTest {

    @Enumeration(name = "CsvStatuses")
    public enum Status {
        @EnumLabel("Новый") NEW,
        APPROVED
    }

    @Catalog(name = "CsvTickets")
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class Ticket extends CatalogObject {
        @Attribute private Status status;
    }

    private TestEntityStore.Stack stack;
    private CatalogDescriptor tickets;
    private CatalogCsvImportService imports;

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
        new SchemaGenerator(registry).execute(jdbi);

        stack = TestEntityStore.builder(ds, registry).build();
        CatalogCommandService commands = new CatalogCommandService(registry, stack.jdbi(), new WebProperties(),
                new CatalogQueryService(registry, jdbi), new AccessService(registry), stack.store());
        imports = new CatalogCsvImportService(stack.jdbi(), commands, new OnnoImportProperties());
    }

    @AfterEach
    void tearDown() {
        stack.close();
    }

    @Test
    void importsConstantNamesAndUuidsAndFailsOnlyTheRowWithALabel() {
        UUID newId = EnumerationPersistence.resolveId(Status.class, Status.NEW);
        byte[] csv = ("Code,Status\n"
                + "T-1,APPROVED\n"
                + "T-2," + newId + "\n"
                + "T-3,Новый\n").getBytes(StandardCharsets.UTF_8);

        ImportResult result = imports.importCatalog(tickets, csv, null, Map.of("code", "Code", "status", "Status"),
                CatalogImportMode.CREATE_ONLY, false, AccessSubject.user("admin", Set.of("ADMIN")));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.row()).isEqualTo(3);
            assertThat(error.message()).contains("must be one of NEW, APPROVED");
        });
        String column = tickets.attributes().stream().filter(a -> a.fieldName().equals("status"))
                .findFirst().orElseThrow().columnName();
        List<Map<String, Object>> stored = stack.jdbi().withHandle(h -> h.createQuery(
                        "SELECT _code, " + column + " AS status FROM " + tickets.tableName() + " ORDER BY _code")
                .mapToMap().list());
        assertThat(stored).extracting(row -> row.get("status")).containsExactly(
                EnumerationPersistence.resolveId(Status.class, Status.APPROVED), newId);
    }
}
