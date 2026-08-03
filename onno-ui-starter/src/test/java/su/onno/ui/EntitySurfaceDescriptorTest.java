package su.onno.ui;

import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntitySurfaceDescriptorTest {

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

    @Test
    void catalogQueryFieldsAcceptLogicalAndStorageNames() {
        EntitySurfaceDescriptor surface = EntitySurfaceDescriptor.catalog(
                scanner.scan(EntityJsonRepresentationTest.Customer.class));

        assertThat(surface.storageColumn("description")).isEqualTo("_description");
        assertThat(surface.storageColumn("_description")).isEqualTo("_description");
        assertThat(surface.storageColumn("taxId")).isEqualTo("tax_id");
        assertThat(surface.storageColumn("tax_id")).isEqualTo("tax_id");
        assertThat(surface.safeSort("taxId")).isEqualTo("tax_id");
    }

    @Test
    void documentQueryFieldsAcceptLogicalAndStorageNames() {
        EntitySurfaceDescriptor surface = EntitySurfaceDescriptor.document(
                scanner.scanDocument(EntityJsonRepresentationTest.Order.class));

        assertThat(surface.storageColumn("date")).isEqualTo("_date");
        assertThat(surface.storageColumn("_date")).isEqualTo("_date");
        assertThat(surface.storageColumn("customer")).isEqualTo("customer_id");
        assertThat(surface.storageColumn("customer_id")).isEqualTo("customer_id");
        assertThat(surface.safeSort("date")).isEqualTo("_date");
        assertThat(surface.isDefaultSort("date")).isFalse();
    }
}
