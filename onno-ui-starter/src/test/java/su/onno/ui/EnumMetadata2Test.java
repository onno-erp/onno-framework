package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Enumeration;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnumMetadata2Test {

    @Enumeration(name = "Em2Statuses", title = "Statuses")
    enum Status {
        NEW,
        DONE
    }

    @Catalog(name = "Em2Items")
    static class Item extends CatalogObject {
        @Attribute
        private Status status;
    }

    @Test
    @SuppressWarnings("unchecked")
    void enumMetadataUsesTitleAndValuesWithoutLegacyEnumName() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerEnumeration(scanner.scanEnumeration(Status.class));
        var catalog = scanner.scan(Item.class);
        registry.registerCatalog(catalog);

        Map<String, Object> described = new ResolvedMetadataService(
                registry, new FieldHintResolver(List.of())).describeCatalog(catalog);
        Map<String, Object> status = ((List<Map<String, Object>>) described.get("attributes"))
                .stream()
                .filter(attribute -> "status".equals(attribute.get("fieldName")))
                .findFirst()
                .orElseThrow();

        assertThat(status)
                .containsEntry("enumTitle", "Statuses")
                .containsKey("enumValues")
                .doesNotContainKey("enumName");
    }
}
