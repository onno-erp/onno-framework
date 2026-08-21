package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DetailWidgetMetadataTest {

    @Catalog(name = "WidgetProducts")
    static class Product extends CatalogObject {}

    static class ProductView implements EntityView<Product> {
        @Override public Class<Product> entity() { return Product.class; }

        @Override
        public void detail(DetailSpec<Product> detail) {
            detail.widget("History").type("productHistory").order(20).config("limit", "5");
            detail.widget("Summary").type("productSummary").order(10).width("half")
                    .hint("Current product summary");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogMetadataCarriesOrderedDetailWidgetsAndEntityBinding() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        var descriptor = scanner.scan(Product.class);
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(descriptor);
        ResolvedMetadataService service = new ResolvedMetadataService(
                registry, new FieldHintResolver(List.of(new ProductView())));

        List<Map<String, Object>> widgets = (List<Map<String, Object>>)
                service.describeCatalog(descriptor).get("detailWidgets");

        assertThat(widgets).extracting(w -> w.get("widgetType"))
                .containsExactly("productSummary", "productHistory");
        assertThat(widgets.get(0))
                .containsEntry("entityType", "catalog")
                .containsEntry("entityName", "WidgetProducts")
                .containsEntry("width", "half")
                .containsEntry("hint", "Current product summary");
        assertThat((Map<String, String>) widgets.get(1).get("extraConfig"))
                .containsEntry("limit", "5");
    }
}
