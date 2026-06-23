package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NewEntityDefaults} seeds a New form's initial row from a fresh instance's field
 * initializers (issue #181): an initialized attribute pre-fills, an uninitialized (null) one is
 * omitted, and a secret never leaks a default. Values are keyed by column name — the shape the
 * form's {@code initial} map reads.
 */
class NewEntityDefaultsTest {

    @Catalog(name = "DraftProducts")
    static class Product extends CatalogObject {
        @Attribute(displayName = "Name", length = 80)
        private String name; // no initializer → must not appear in the seed
        @Attribute(displayName = "Quantity")
        private Integer quantity = 1;
        @Attribute(displayName = "Active")
        private boolean active = true;
        @Attribute(displayName = "Price", precision = 15, scale = 2)
        private BigDecimal price = new BigDecimal("9.99");
        @Attribute(displayName = "Token", secret = true)
        private String token = "do-not-leak"; // a secret default must never be seeded
    }

    private static String column(CatalogDescriptor desc, String fieldName) {
        return desc.attributes().stream()
                .filter(a -> a.fieldName().equals(fieldName))
                .findFirst().orElseThrow().columnName();
    }

    @Test
    void seedsFieldInitializerDefaultsKeyedByColumn() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        CatalogDescriptor desc = scanner.scan(Product.class);
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(desc);

        Map<String, Object> row = NewEntityDefaults.columnValues(desc.javaClass(), desc.attributes(), registry);

        assertThat(row.get(column(desc, "quantity"))).isEqualTo(1);
        assertThat(row.get(column(desc, "active"))).isEqualTo(true);
        assertThat(row.get(column(desc, "price"))).isEqualTo(new BigDecimal("9.99"));
        // A field with no initializer is omitted entirely (not a null entry).
        assertThat(row).doesNotContainKey(column(desc, "name"));
        // A secret default is never exposed.
        assertThat(row).doesNotContainKey(column(desc, "token"));
    }
}
