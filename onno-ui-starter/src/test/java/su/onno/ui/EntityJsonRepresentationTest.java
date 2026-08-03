package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.security.SecretRedactor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityJsonRepresentationTest {

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
    private final CatalogDescriptor customer = scanner.scan(Customer.class);
    private final DocumentDescriptor order = scanner.scanDocument(Order.class);

    @Test
    void catalogLogicalRepresentationMapsSystemAttributesSidecarsSecretsAndTreeChildren() {
        UUID id = UUID.randomUUID();
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("_id", UUID.randomUUID());
        child.put("_description", "Child");
        child.put("tax_id", "CHILD-TAX");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_id", id);
        raw.put("_code", "C-1");
        raw.put("_description", "Acme");
        raw.put("_deletion_mark", false);
        raw.put("_is_folder", true);
        raw.put("_parent", null);
        raw.put("_version", 4);
        raw.put("tax_id", "123");
        raw.put("customer_id", UUID.randomUUID());
        raw.put("customer_id_display", "Primary contact");
        raw.put("customer_id_ref", Map.of("id", UUID.randomUUID().toString(), "display", "Primary contact"));
        raw.put("customer_id_code", "P-1");
        raw.put("customer_id_avatar", "/media/p-1.png");
        raw.put("customer_id_color", "#00AA00");
        raw.put("api_key", SecretRedactor.SET);
        raw.put("_actions", Map.of("approve", Map.of("enabled", true)));
        raw.put("_style", "success");
        raw.put("children", List.of(child));

        Map<String, Object> logical = EntityJsonRepresentation.catalog(
                customer, raw, EntityJsonRepresentation.Mode.LOGICAL);

        assertThat(logical)
                .containsEntry("id", id)
                .containsEntry("code", "C-1")
                .containsEntry("description", "Acme")
                .containsEntry("deletionMark", false)
                .containsEntry("folder", true)
                .containsEntry("parent", null)
                .containsEntry("version", 4)
                .containsEntry("taxId", "123")
                .containsEntry("customerDisplay", "Primary contact")
                .containsEntry("customerCode", "P-1")
                .containsEntry("customerAvatar", "/media/p-1.png")
                .containsEntry("customerColor", "#00AA00")
                .containsEntry("apiKey", SecretRedactor.SET)
                .containsEntry("style", "success")
                .containsKeys("customerRef", "actions")
                .doesNotContainKeys("_id", "tax_id", "customer_id_display", "api_key");
        assertThat(castRows(logical.get("children"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("description", "Child")
                .containsEntry("taxId", "CHILD-TAX")
                .doesNotContainKeys("_description", "tax_id"));
    }

    @Test
    void documentLogicalRepresentationMapsHeaderAndTabularRows() {
        UUID id = UUID.randomUUID();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("_id", UUID.randomUUID());
        line.put("_parent_id", id);
        line.put("_line_number", 1);
        line.put("unit_price", new BigDecimal("12.50"));
        line.put("product_id", UUID.randomUUID());
        line.put("product_id_display", "Widget");
        line.put("product_id_color", "#112233");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("_id", id);
        raw.put("_number", "SO-1");
        raw.put("_date", "2026-08-03T10:15:00");
        raw.put("_posted", false);
        raw.put("_deletion_mark", false);
        raw.put("_version", 2);
        raw.put("customer_id", UUID.randomUUID());
        raw.put("customer_id_display", "Acme");
        raw.put("items", List.of(line));

        Map<String, Object> logical = EntityJsonRepresentation.document(
                order, raw, EntityJsonRepresentation.Mode.LOGICAL);

        assertThat(logical)
                .containsEntry("id", id)
                .containsEntry("number", "SO-1")
                .containsEntry("date", "2026-08-03T10:15:00")
                .containsEntry("posted", false)
                .containsEntry("deletionMark", false)
                .containsEntry("version", 2)
                .containsEntry("customerDisplay", "Acme")
                .doesNotContainKeys("_id", "_number", "customer_id_display");
        assertThat(castRows(logical.get("items"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("parentId", id)
                .containsEntry("lineNumber", 1)
                .containsEntry("unitPrice", new BigDecimal("12.50"))
                .containsEntry("productDisplay", "Widget")
                .containsEntry("productColor", "#112233")
                .doesNotContainKeys("_parent_id", "_line_number", "unit_price", "product_id_display"));
    }

    @Test
    void storageRepresentationReturnsTheExistingShapeUnchanged() {
        Map<String, Object> raw = new LinkedHashMap<>(Map.of("_id", UUID.randomUUID(), "tax_id", "123"));

        assertThat(EntityJsonRepresentation.catalog(customer, raw, EntityJsonRepresentation.Mode.STORAGE))
                .isSameAs(raw);
        assertThat(EntityJsonRepresentation.parse("storage"))
                .isEqualTo(EntityJsonRepresentation.Mode.STORAGE);
        assertThat(EntityJsonRepresentation.parse(null))
                .isEqualTo(EntityJsonRepresentation.Mode.LOGICAL);
    }

    @Test
    void unknownRepresentationIsRejected() {
        assertThatThrownBy(() -> EntityJsonRepresentation.parse("v3"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("logical")
                .hasMessageContaining("storage");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Catalog(name = "Customers")
    static class Customer extends CatalogObject {
        @Attribute(name = "tax_id")
        private String taxId;

        @Attribute(name = "customer_id")
        private UUID customer;

        @Attribute(name = "api_key", secret = true)
        private String apiKey;
    }

    @Document(name = "Orders")
    static class Order extends DocumentObject {
        @Attribute(name = "customer_id")
        private UUID customer;

        @TabularSection(name = "items")
        private List<OrderLine> items = new ArrayList<>();
    }

    static class OrderLine extends TabularSectionRow {
        @Attribute(name = "unit_price")
        private BigDecimal unitPrice;

        @Attribute(name = "product_id")
        private UUID product;
    }
}
