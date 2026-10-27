package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ref picker's open/clear buttons (#409) reach the form metadata as resolved booleans for
 * header refs and tabular-section row refs alike: "open" defaults on, "clear" defaults on for
 * optional refs, and a required ref is never clearable whatever its hint says.
 */
class RefPickerButtonsMetadataTest {

    @Catalog(name = "ButtonCustomers")
    static class Customer extends CatalogObject {
    }

    @Catalog(name = "ButtonBooks")
    static class Book extends CatalogObject {
    }

    @Document(name = "ButtonOrders")
    static class Order extends DocumentObject {
        @Attribute
        private Ref<Customer> customer;

        @Attribute
        private Ref<Customer> payer;

        @Attribute(required = true)
        private Ref<Customer> owner;

        @Attribute(required = true)
        private Ref<Customer> approver;

        @Attribute(length = 100)
        private String note;

        @TabularSection(name = "lines")
        private List<Line> lines = new ArrayList<>();
    }

    static class Line extends TabularSectionRow {
        @Attribute
        private Ref<Book> book;

        @Attribute
        private Ref<Book> gift;

        @Attribute(required = true)
        private Ref<Book> mandatory;
    }

    static class OrderView implements EntityView {
        @Override
        public Class<?> entity() {
            return Order.class;
        }

        @Override
        public void fields(EntityConfigBuilder fields) {
            fields.field("payer").openButton(false).clearButton(false);
            fields.field("approver").clearButton(true);
            fields.field("lines.book").openButton(false);
            fields.field("lines.gift").clearButton(false);
        }
    }

    private Map<String, Map<String, Object>> header;
    private Map<String, Map<String, Object>> lines;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void describe() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Customer.class));
        registry.registerCatalog(scanner.scan(Book.class));
        registry.registerDocument(scanner.scanDocument(Order.class));
        ResolvedMetadataService metadata =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(new OrderView())));

        Map<String, Object> described = metadata.describeDocument(scanner.scanDocument(Order.class));
        header = byField((List<Map<String, Object>>) described.get("attributes"));
        List<Map<String, Object>> sections = (List<Map<String, Object>>) described.get("tabularSections");
        lines = byField((List<Map<String, Object>>) sections.getFirst().get("attributes"));
    }

    @Test
    void anOptionalRefWithoutHintsShowsBothButtons() {
        assertThat(header.get("customer"))
                .containsEntry("refOpenButton", true)
                .containsEntry("refClearButton", true);
    }

    @Test
    void hintsTurnTheButtonsOff() {
        assertThat(header.get("payer"))
                .containsEntry("refOpenButton", false)
                .containsEntry("refClearButton", false);
    }

    @Test
    void aRequiredRefIsNeverClearableEvenWhenTheHintAsksForIt() {
        assertThat(header.get("owner"))
                .containsEntry("refOpenButton", true)
                .containsEntry("refClearButton", false);
        assertThat(header.get("approver"))
                .containsEntry("refOpenButton", true)
                .containsEntry("refClearButton", false);
    }

    @Test
    void tabularSectionRowRefsHonourSectionScopedHints() {
        assertThat(lines.get("book"))
                .containsEntry("refOpenButton", false)
                .containsEntry("refClearButton", true);
        assertThat(lines.get("gift"))
                .containsEntry("refOpenButton", true)
                .containsEntry("refClearButton", false);
        assertThat(lines.get("mandatory"))
                .containsEntry("refOpenButton", true)
                .containsEntry("refClearButton", false);
    }

    @Test
    void nonRefFieldsCarryNoPickerButtons() {
        assertThat(header.get("note")).doesNotContainKeys("refOpenButton", "refClearButton");
    }

    private static Map<String, Map<String, Object>> byField(List<Map<String, Object>> attributes) {
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> attribute : attributes) {
            result.put((String) attribute.get("fieldName"), attribute);
        }
        return result;
    }
}
