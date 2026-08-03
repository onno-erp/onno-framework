package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntitySurfaceDescriptorTest {

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
                15, 2, true, true, true,
                0, "", "", "", AttributeDescriptor.Constraints.NONE, secret);
    }
}
