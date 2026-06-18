package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field's optional help text ({@code .field(...).hint(...)}) flows from the {@link EntityView}
 * DSL into the resolved attribute metadata as {@code hint}, and defaults to an empty string when
 * not set — the same merge path as {@code placeholder}/{@code format}.
 */
class FieldHintMetadataTest {

    @Catalog(name = "HintProducts")
    static class Product extends CatalogObject {
        @Attribute(displayName = "Name", length = 80)
        private String name;
        @Attribute(displayName = "SKU", length = 40)
        private String sku;
    }

    static class ProductView implements EntityView {
        @Override
        public Class<?> entity() {
            return Product.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("sku").hint("Stock-keeping unit; unique per product.");
        }
    }

    private ResolvedMetadataService serviceWith(EntityView... views) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Product.class));
        return new ResolvedMetadataService(registry, new FieldHintResolver(List.of(views)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attr(Map<String, Object> described, String fieldName) {
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) described.get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @Test
    void describeCatalog_carriesFieldHint() {
        ResolvedMetadataService svc = serviceWith(new ProductView());
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Product.class));

        assertThat(attr(described, "sku").get("hint"))
                .isEqualTo("Stock-keeping unit; unique per product.");
        // Fields with no hint emit a blank string, never null — so the client can read it directly.
        assertThat(attr(described, "name").get("hint")).isEqualTo("");
    }

    @Test
    void describeCatalog_noView_defaultsHintToBlank() {
        ResolvedMetadataService svc = serviceWith();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Product.class));
        assertThat(attr(described, "sku").get("hint")).isEqualTo("");
    }
}
