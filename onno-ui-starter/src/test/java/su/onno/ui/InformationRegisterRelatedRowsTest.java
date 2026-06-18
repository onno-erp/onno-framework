package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Dimension;
import su.onno.annotations.Document;
import su.onno.annotations.InformationRegister;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.InformationRegisterDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.InformationRecord;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * The read side of a register-backed related-list panel (#110 optional): an information register
 * with two ref dimensions models the M:N junction (1C's idiomatic store), and
 * {@link InformationRegisterQueryService#relatedRows} returns the rows scoped to one parent via the
 * {@code via} dimension, with the {@code display} dimension's ref resolved to its description —
 * driving the same panel from either side.
 */
class InformationRegisterRelatedRowsTest {

    @Catalog(name = "IrClients")
    static class Client extends CatalogObject {
    }

    @Document(name = "IrBookings")
    static class Booking extends DocumentObject {
    }

    /** A non-periodic junction register: Booking↔Client with a {@code relation} attribute per link. */
    @InformationRegister(name = "IrBookingClient")
    static class BookingClientReg extends InformationRecord {
        @Dimension
        private Ref<Booking> booking;
        @Dimension
        private Ref<Client> client;
        @Attribute(length = 40)
        private String relation;
    }

    private Jdbi jdbi;
    private InformationRegisterQueryService service;
    private InformationRegisterDescriptor regDesc;
    private CatalogDescriptor clientDesc;

    private final UUID booking1 = UUID.randomUUID();
    private final UUID booking2 = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID grace = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:irrelated" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Client.class));
        registry.registerDocument(scanner.scanDocument(Booking.class));
        registry.registerInformationRegister(scanner.scanInformationRegister(BookingClientReg.class));
        new SchemaGenerator(registry).execute(jdbi);

        service = new InformationRegisterQueryService(registry, jdbi);
        regDesc = registry.getInformationRegisterDescriptor(BookingClientReg.class);
        clientDesc = registry.allCatalogs().stream()
                .filter(c -> c.javaClass() == Client.class).findFirst().orElseThrow();

        insertClient(ada, "C-1", "Ada Lovelace");
        insertClient(grace, "C-2", "Grace Hopper");
        // booking1 links two clients; booking2 links one — so the via scoping is observable.
        insertJunction(booking1, ada, "Primary guest");
        insertJunction(booking1, grace, "Co-guest");
        insertJunction(booking2, ada, "Primary guest");
    }

    @Test
    void scopesRowsToParentAndResolvesDisplayRef() {
        List<Map<String, Object>> rows = service.relatedRows(regDesc, dim("booking"), booking1);

        assertThat(rows).hasSize(2);
        // The display dimension (client) resolves to each client's description.
        assertThat(rows).extracting(r -> r.get(dim("client") + "_display"))
                .containsExactlyInAnyOrder("Ada Lovelace", "Grace Hopper");
        // The non-ref attribute rides along on the row.
        assertThat(rows).extracting(r -> ci(r, "relation"))
                .containsExactlyInAnyOrder("Primary guest", "Co-guest");
    }

    @Test
    void otherParentSeesOnlyItsOwnRows() {
        List<Map<String, Object>> rows = service.relatedRows(regDesc, dim("booking"), booking2);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(dim("client") + "_display")).isEqualTo("Ada Lovelace");
    }

    @Test
    void parentWithNoLinksReturnsEmpty() {
        assertThat(service.relatedRows(regDesc, dim("booking"), UUID.randomUUID())).isEmpty();
    }

    /** The DB column for a register dimension by field name (never hardcode the naming strategy). */
    private String dim(String fieldName) {
        return regDesc.dimensions().stream()
                .filter(d -> d.fieldName().equals(fieldName))
                .map(AttributeDescriptor::columnName)
                .findFirst().orElseThrow();
    }

    private void insertClient(UUID id, String code, String description) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + clientDesc.tableName()
                        + " (_id, _code, _description) VALUES (:id, :code, :description)")
                .bind("id", id).bind("code", code).bind("description", description).execute());
    }

    private void insertJunction(UUID booking, UUID client, String relation) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + regDesc.tableName()
                        + " (_id, " + dim("booking") + ", " + dim("client") + ", relation)"
                        + " VALUES (:id, :booking, :client, :relation)")
                .bind("id", UUID.randomUUID()).bind("booking", booking).bind("client", client)
                .bind("relation", relation).execute());
    }

    /** Case-insensitive row lookup — engines differ on returned column-name case. */
    private static Object ci(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        v = row.get(key.toUpperCase(Locale.ROOT));
        return v != null ? v : row.get(key.toLowerCase(Locale.ROOT));
    }
}
