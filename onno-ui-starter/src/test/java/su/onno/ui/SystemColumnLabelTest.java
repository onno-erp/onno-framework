package su.onno.ui;

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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code .field(name).label("…")} hint relabels the built-in <b>system columns</b>
 * (code/description on catalogs; number/date/posted on documents) and custom attributes alike, so a
 * localized app can author Russian (or any) labels for the form/detail surfaces from the layout DSL
 * — the form/detail counterpart to {@link ListSpec#label(String, String)}, which only touched the
 * list header (#154). Unset columns keep their English fallback.
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

    private ResolvedMetadataService serviceWith(EntityView... views) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Product.class));
        registry.registerDocument(scanner.scanDocument(Order.class));
        return new ResolvedMetadataService(registry, new FieldHintResolver(List.of(views)));
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
    void catalogSystemColumnLabel_overridesEnglishDefault() {
        ResolvedMetadataService svc = serviceWith(catalogView(f -> f.field("code").label("Код")));
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Product.class));

        assertThat(systemColumn(described, "code").get("displayName")).isEqualTo("Код");
        // An unrelabelled system column keeps its English fallback.
        assertThat(systemColumn(described, "description").get("displayName")).isEqualTo("Description");
    }

    @Test
    void documentSystemColumnLabels_overrideEnglishDefaults() {
        ResolvedMetadataService svc = serviceWith(documentView(f -> f.field("number").label("Номер")
                .field("date").label("Дата")
                .field("posted").label("Статус")));
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeDocument(scanner.scanDocument(Order.class));

        assertThat(systemColumn(described, "number").get("displayName")).isEqualTo("Номер");
        assertThat(systemColumn(described, "date").get("displayName")).isEqualTo("Дата");
        assertThat(systemColumn(described, "posted").get("displayName")).isEqualTo("Статус");
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
    void attributeLabel_overridesAttributeDisplayName() {
        ResolvedMetadataService svc = serviceWith(catalogView(f -> f.field("name").label("Имя")));
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Product.class));

        // The DSL label wins over @Attribute(displayName = "Name").
        assertThat(attribute(described, "name").get("displayName")).isEqualTo("Имя");
    }

    @Test
    void noLabelHint_keepsAttributeDisplayName() {
        ResolvedMetadataService svc = serviceWith();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Product.class));
        assertThat(attribute(described, "name").get("displayName")).isEqualTo("Name");
    }

    private EntityView catalogView(Consumer<EntityConfigBuilder> fields) {
        return new EntityView() {
            @Override
            public Class<?> entity() {
                return Product.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                fields.accept(f);
            }
        };
    }

    private EntityView documentView(Consumer<EntityConfigBuilder> fields) {
        return new EntityView() {
            @Override
            public Class<?> entity() {
                return Order.class;
            }

            @Override
            public void fields(EntityConfigBuilder f) {
                fields.accept(f);
            }
        };
    }
}
