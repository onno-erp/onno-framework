package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.EnumerationDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link RefResolver#resolveAttributes} expands an enum UUID column into {@code {col}_display} using
 * the value's localized {@code @EnumLabel}, falling back to the constant name when unlabelled.
 * Enum resolution reads only the in-memory registry, so the JDBI handle is never touched here.
 */
class RefResolverEnumLabelTest {

    @Enumeration(name = "RrStatuses")
    enum Status {
        @EnumLabel("Новый") NEW,
        SHIPPED
    }

    @Catalog(name = "RrOrders")
    static class Order extends CatalogObject {
        @Attribute
        private Status status;
    }

    @Test
    void resolveEnumColumn_usesLabelForDisplay_andFallsBackToName() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        EnumerationDescriptor enumDesc = scanner.scanEnumeration(Status.class);
        registry.registerEnumeration(enumDesc);
        CatalogDescriptor order = scanner.scan(Order.class);
        registry.registerCatalog(order);

        AttributeDescriptor statusAttr = order.attributes().stream()
                .filter(a -> a.fieldName().equals("status"))
                .findFirst().orElseThrow();
        UUID newId = enumDesc.values().stream().filter(v -> v.name().equals("NEW"))
                .findFirst().orElseThrow().id();
        UUID shippedId = enumDesc.values().stream().filter(v -> v.name().equals("SHIPPED"))
                .findFirst().orElseThrow().id();

        Map<String, Object> labelled = new HashMap<>();
        labelled.put(statusAttr.columnName(), newId);
        Map<String, Object> unlabelled = new HashMap<>();
        unlabelled.put(statusAttr.columnName(), shippedId);

        RefResolver resolver = new RefResolver(registry, null);
        resolver.resolveAttributes(List.of(labelled, unlabelled), order.attributes());

        assertThat(labelled.get(statusAttr.columnName() + "_display")).isEqualTo("Новый");
        assertThat(unlabelled.get(statusAttr.columnName() + "_display")).isEqualTo("SHIPPED");
    }
}
