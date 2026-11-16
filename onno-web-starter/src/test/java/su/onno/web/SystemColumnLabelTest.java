package su.onno.web;

import su.onno.validation.CheckRegistry;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * System columns (code/description on catalogs; number/date/posted on documents) and attributes
 * carry their English/annotation display names in the resolved metadata; localized labels are the
 * client's job now.
 */
class SystemColumnLabelTest {

    @Catalog(name = "LblProducts")
    static class Product extends CatalogObject {
        @Attribute(displayName = "Name", length = 80)
        private String name;
    }

    @Document(name = "LblOrders")
    static class Order extends DocumentObject {
        @Attribute(displayName = "Channel", length = 40)
        private String channel;
    }

    private ResolvedMetadataService serviceWith() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Product.class));
        registry.registerDocument(scanner.scanDocument(Order.class));
        return new ResolvedMetadataService(registry, new CheckRegistry(List.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> systemColumn(Map<String, Object> described, String fieldName) {
        List<Map<String, Object>> cols = (List<Map<String, Object>>) described.get("systemColumns");
        return cols.stream().filter(c -> fieldName.equals(c.get("fieldName"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attribute(Map<String, Object> described, String fieldName) {
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) described.get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @Test
    void noLabelHint_keepsEnglishSystemColumnDefaults() {
        ResolvedMetadataService svc = serviceWith();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> catalog = svc.describeCatalog(scanner.scan(Product.class));
        assertThat(systemColumn(catalog, "code").get("displayName")).isEqualTo("Code");
        assertThat(systemColumn(catalog, "description").get("displayName")).isEqualTo("Description");

        Map<String, Object> document = svc.describeDocument(scanner.scanDocument(Order.class));
        assertThat(systemColumn(document, "number").get("displayName")).isEqualTo("Number");
        assertThat(systemColumn(document, "date").get("displayName")).isEqualTo("Date");
        assertThat(systemColumn(document, "posted").get("displayName")).isEqualTo("Status");
    }

    @Test
    void noLabelHint_keepsAttributeDisplayName() {
        ResolvedMetadataService svc = serviceWith();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Product.class));
        assertThat(attribute(described, "name").get("displayName")).isEqualTo("Name");
    }
}
