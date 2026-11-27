package su.onno.service;

import org.junit.jupiter.api.Test;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntitySurfaceDescriptorTest {

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

    @Test
    void hierarchyColumnsAreOnlyExposedOnHierarchicalCatalogs() {
        var flat = EntitySurfaceDescriptor.catalog(scanner.scan(EntityJsonRepresentationTest.Customer.class));
        assertThatThrownBy(() -> flat.whereSchema().resolve("folder")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> flat.whereSchema().resolve("parent")).isInstanceOf(ServiceException.class);
        assertThat(flat.whereSchema().columns()).doesNotContainKeys("_is_folder", "_parent");
        assertThat(flat.widgetSystemColumns()).doesNotContain("_is_folder");

        var tree = EntitySurfaceDescriptor.catalog(scanner.scan(CatalogHierarchyWriteTest.Node.class));
        assertThat(tree.storageColumn("folder")).isEqualTo("_is_folder");
        assertThat(tree.storageColumn("parent")).isEqualTo("_parent");
        assertThat(tree.whereSchema().columns()).containsKey("_is_folder");
        assertThat(tree.widgetSystemColumns()).contains("_is_folder");
    }

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

    @Test
    void secretAttributesAreExcludedFromQueryAllowlists() {
        AttributeDescriptor publicName = attribute("public_name", false);
        AttributeDescriptor apiKey = attribute("api_key", true);
        CatalogDescriptor catalog = new CatalogDescriptor(
                "Accounts", "Accounts", "accounts", Object.class,
                9, false, true, "", "", List.of(), List.of(), List.of(publicName, apiKey));

        EntitySurfaceDescriptor surface = EntitySurfaceDescriptor.catalog(catalog);

        assertThat(surface.columnNames()).contains("public_name").doesNotContain("api_key");
        assertThat(surface.filterableColumns()).doesNotContain("api_key");
        assertThat(surface.sortableColumns()).doesNotContain("api_key");
        assertThat(surface.safeSort("api_key")).isEqualTo("_code");
    }

    private static AttributeDescriptor attribute(String name, boolean secret) {
        return new AttributeDescriptor(
                name, name, name, String.class,
                255, false, false, "",
                15, 2, AttributeDescriptor.Constraints.NONE, secret);
    }
}
