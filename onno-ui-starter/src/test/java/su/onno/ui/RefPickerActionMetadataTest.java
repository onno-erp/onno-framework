package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ref-picker secondary field (issue #184) and custom DETAIL action placement override
 * (issue #183) both flow from the {@link EntityView} DSL into resolved metadata:
 * {@code f.field(ref).refSecondary("phone")} surfaces as the target's column on the attribute, and
 * {@code f.action(key).primary()} appears in the action-override map for <em>custom</em> keys (not
 * just the built-in post/edit/delete).
 */
class RefPickerActionMetadataTest {

    @Catalog(name = "PickCustomers")
    static class Customer extends CatalogObject {
        @Attribute(displayName = "Phone", length = 40)
        private String phone;
    }

    @Document(name = "PickOrders")
    static class Order extends DocumentObject {
        @Attribute(displayName = "Customer")
        private Ref<Customer> customer;
    }

    static class OrderView implements EntityView {
        @Override
        public Class<?> entity() {
            return Order.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("customer").refSecondary("phone");
            // A custom DETAIL action promoted to a primary button.
            f.action("advanceStatus").primary();
        }
    }

    private ResolvedMetadataService serviceWith(EntityView view) {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Customer.class));
        registry.registerDocument(scanner.scanDocument(Order.class));
        return new ResolvedMetadataService(registry, new FieldHintResolver(List.of(view)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attr(Map<String, Object> described, String fieldName) {
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) described.get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @Test
    void refSecondary_resolvesToTargetColumn() {
        ResolvedMetadataService svc = serviceWith(new OrderView());
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeDocument(scanner.scanDocument(Order.class));

        // The configured "phone" field on the target resolves to its column key, which the picker
        // reads from each option's payload.
        assertThat(attr(described, "customer").get("refSecondary")).isEqualTo("phone");
    }

    @Test
    void refSecondary_absentWhenNotConfigured() {
        ResolvedMetadataService svc = serviceWith(new EntityView() {
            @Override
            public Class<?> entity() {
                return Order.class;
            }
        });
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());

        Map<String, Object> described = svc.describeDocument(scanner.scanDocument(Order.class));
        assertThat(attr(described, "customer")).doesNotContainKey("refSecondary");
    }

    @Test
    void actionOverrides_includesCustomDetailActionKeys() {
        ResolvedMetadataService svc = serviceWith(new OrderView());

        Map<String, String> overrides = svc.actionOverrides(Order.class);

        // A custom action key is present (so detailActions can promote it), not just post/edit/delete.
        assertThat(overrides).containsEntry("advanceStatus", "primary");
    }
}
