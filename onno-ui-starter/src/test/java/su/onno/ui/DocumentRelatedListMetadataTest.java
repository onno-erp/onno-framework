package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Dimension;
import su.onno.annotations.Document;
import su.onno.annotations.InformationRegister;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.InformationRecord;
import su.onno.types.Ref;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ResolvedMetadataService#describeDocument} resolves a document view's related-list panels
 * exactly like a catalog's (#110), over either kind of junction (#110 optional): a join catalog
 * (editable) or an information register (read-only, 1C's idiomatic M:N store). So a Booking
 * document can surface its Clients — the reverse side of a Booking↔Client junction — not just the
 * Client catalog surfacing its Bookings.
 */
class DocumentRelatedListMetadataTest {

    @Catalog(name = "RlClients")
    static class Client extends CatalogObject {
    }

    @Document(name = "RlBookings")
    static class Booking extends DocumentObject {
    }

    /** A join-catalog junction: two refs model Booking↔Client, with a {@code relation} attribute. */
    @Catalog(name = "RlBookingClient")
    static class BookingClient extends CatalogObject {
        @Attribute
        private Ref<Booking> booking;
        @Attribute
        private Ref<Client> client;
        @Attribute(length = 40)
        private String relation;
    }

    /** An information-register junction: two ref dimensions model the same M:N (1C-idiomatic). */
    @InformationRegister(name = "RlBookingClientReg")
    static class BookingClientRegister extends InformationRecord {
        @Dimension
        private Ref<Booking> booking;
        @Dimension
        private Ref<Client> client;
    }

    private ResolvedMetadataService serviceWith(EntityView view) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Client.class));
        registry.registerCatalog(scanner.scan(BookingClient.class));
        registry.registerDocument(scanner.scanDocument(Booking.class));
        registry.registerInformationRegister(scanner.scanInformationRegister(BookingClientRegister.class));
        return new ResolvedMetadataService(registry, new FieldHintResolver(List.of(view)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> describeBookingRelatedLists(EntityView view) {
        ResolvedMetadataService svc = serviceWith(view);
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        Map<String, Object> described = svc.describeDocument(scanner.scanDocument(Booking.class));
        return (List<Map<String, Object>>) described.get("relatedLists");
    }

    @SuppressWarnings("unchecked")
    @Test
    void document_catalogJunction_resolvesEditablePanel() {
        List<Map<String, Object>> related = describeBookingRelatedLists(view(f ->
                f.relatedList("clients", BookingClient.class)
                        .via("booking").display("client").columns("client", "relation").label("Clients")));

        assertThat(related).hasSize(1);
        Map<String, Object> rl = related.get(0);
        assertThat(rl.get("name")).isEqualTo("clients");
        assertThat(rl.get("label")).isEqualTo("Clients");
        assertThat(rl.get("joinCatalog")).isEqualTo("RlBookingClient");
        // A join-catalog junction is editable (the form can add/remove join rows).
        assertThat(rl.get("sourceKind")).isEqualTo("catalog");
        assertThat(rl.get("readOnly")).isEqualTo(false);
        assertThat(rl.get("viaField")).isEqualTo("booking");
        assertThat(rl.get("displayField")).isEqualTo("client");
        assertThat(rl.get("target")).isEqualTo("RlClients");
        assertThat(rl.get("targetKind")).isEqualTo("catalog");
        assertThat(rl.get("showInDetail")).isEqualTo(true);

        List<Map<String, Object>> columns = (List<Map<String, Object>>) rl.get("columns");
        assertThat(columns).extracting(c -> c.get("fieldName")).containsExactly("client", "relation");
    }

    @SuppressWarnings("unchecked")
    @Test
    void document_informationRegisterJunction_resolvesReadOnlyPanel() {
        List<Map<String, Object>> related = describeBookingRelatedLists(view(f ->
                f.relatedList("clients", BookingClientRegister.class)
                        .via("booking").display("client").label("Clients")));

        assertThat(related).hasSize(1);
        Map<String, Object> rl = related.get(0);
        // A register junction reads in both directions but is read-only (no generic info-register
        // write yet) — the client renders rows without add/remove.
        assertThat(rl.get("sourceKind")).isEqualTo("register");
        assertThat(rl.get("readOnly")).isEqualTo(true);
        assertThat(rl.get("joinCatalog")).isEqualTo("RlBookingClientReg");
        assertThat(rl.get("viaField")).isEqualTo("booking");
        assertThat(rl.get("displayField")).isEqualTo("client");
        assertThat(rl.get("target")).isEqualTo("RlClients");
        assertThat(rl.get("targetKind")).isEqualTo("catalog");
        assertThat(rl.get("showInDetail")).isEqualTo(true);

        // The display ref dimension resolves as the row's primary column even with no columns().
        List<Map<String, Object>> columns = (List<Map<String, Object>>) rl.get("columns");
        assertThat(columns).extracting(c -> c.get("fieldName")).containsExactly("client");
        assertThat(columns.get(0).get("isRef")).isEqualTo(true);
        assertThat(columns.get(0).get("refTarget")).isEqualTo("RlClients");
    }

    @SuppressWarnings("unchecked")
    @Test
    void document_dropsPanelWhenViaIsNotARefOnTheJunction() {
        // "owner" is not a ref dimension on the register — the panel can't resolve, so it's dropped.
        List<Map<String, Object>> related = describeBookingRelatedLists(view(f ->
                f.relatedList("clients", BookingClientRegister.class).via("owner").display("client")));

        assertThat(related).isEmpty();
    }

    /** A Booking view whose only configuration is the given related-list declaration. */
    private static EntityView view(java.util.function.Consumer<EntityConfigBuilder> fields) {
        return new EntityView() {
            @Override
            public Class<?> entity() {
                return Booking.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                fields.accept(f);
            }
        };
    }
}
