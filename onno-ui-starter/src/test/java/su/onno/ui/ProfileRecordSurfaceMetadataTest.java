package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A profile-specific {@link EntityView} shapes the record surface (form fields, detail widgets,
 * action placement, form validations) for that profile, not only its list — the same "specific view
 * wins, else default" rule {@link UiViewResolver} applies to lists. Other profiles keep the default.
 */
class ProfileRecordSurfaceMetadataTest {

    @Catalog(name = "ProfileProjects")
    static class Project extends CatalogObject {
        @Attribute(displayName = "Name", length = 80)
        private String name;
        @Attribute(displayName = "Billing mode", length = 40)
        private String billingMode;
    }

    static class StaffView implements EntityView {
        @Override
        public Class<?> entity() {
            return Project.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("billingMode").hint("Internal bookkeeping.");
            f.action("archive").hidden();
            f.validation("staffCheck", FormValidator.class);
        }

        @Override
        public void detail(DetailSpec detail) {
            detail.widget("Ops").type("opsPanel");
        }
    }

    static class CustomerView implements EntityView {
        @Override
        public Class<?> entity() {
            return Project.class;
        }

        @Override
        public String profile() {
            return "customer";
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("name").label("Project");
            f.field("billingMode").hideInDetail();
            f.validation("customerCheck", FormValidator.class);
        }

        @Override
        public void detail(DetailSpec detail) {
            detail.widget("Console").type("consolePanel");
        }
    }

    private final MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
    private final CatalogDescriptor descriptor = scanner.scan(Project.class);

    private ResolvedMetadataService serviceWith(EntityView... views) {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(descriptor);
        return new ResolvedMetadataService(registry, new FieldHintResolver(List.of(views)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attr(Map<String, Object> described, String fieldName) {
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) described.get("attributes");
        return attrs.stream().filter(a -> fieldName.equals(a.get("fieldName"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<String> widgetTypes(Map<String, Object> described) {
        return ((List<Map<String, Object>>) described.get("detailWidgets")).stream()
                .map(w -> (String) w.get("widgetType")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> validationKeys(Map<String, Object> described) {
        return ((List<Map<String, Object>>) described.get("formValidations")).stream()
                .map(v -> (String) v.get("key")).toList();
    }

    @Test
    void aProfileWithItsOwnViewGetsThatViewsRecordSurface() {
        ResolvedMetadataService svc = serviceWith(new StaffView(), new CustomerView());

        Map<String, Object> customer = svc.describeCatalog(descriptor, "customer");

        assertThat(attr(customer, "name").get("displayName")).isEqualTo("Project");
        assertThat(attr(customer, "billingMode").get("visibleInDetail")).isEqualTo(false);
        // Whole-view replacement, not a merge: the staff hint does not leak into the customer view.
        assertThat(attr(customer, "billingMode").get("hint")).isEqualTo("");
        assertThat(widgetTypes(customer)).containsExactly("consolePanel");
        assertThat(validationKeys(customer)).containsExactly("customerCheck");
        assertThat(svc.actionOverrides(Project.class, "customer")).isEmpty();
    }

    @Test
    void theDefaultAndUnrelatedProfilesKeepTheDefaultView() {
        ResolvedMetadataService svc = serviceWith(new StaffView(), new CustomerView());

        for (Map<String, Object> described : List.of(svc.describeCatalog(descriptor),
                svc.describeCatalog(descriptor, "default"), svc.describeCatalog(descriptor, "warehouse"))) {
            assertThat(attr(described, "name").get("displayName")).isEqualTo("Name");
            assertThat(attr(described, "billingMode").get("hint")).isEqualTo("Internal bookkeeping.");
            assertThat(widgetTypes(described)).containsExactly("opsPanel");
            assertThat(validationKeys(described)).containsExactly("staffCheck");
        }
        assertThat(svc.actionOverrides(Project.class)).containsEntry("archive", "hidden");
    }

    @Test
    void aProfileOnlyViewShapesThatProfileWithoutTouchingTheDefault() {
        ResolvedMetadataService svc = serviceWith(new CustomerView());

        assertThat(widgetTypes(svc.describeCatalog(descriptor, "customer"))).containsExactly("consolePanel");
        assertThat(widgetTypes(svc.describeCatalog(descriptor))).isEmpty();
        assertThat(attr(svc.describeCatalog(descriptor), "name").get("displayName")).isEqualTo("Name");
    }

    @Test
    void profilelessLookupsFindValidatorsDeclaredOnAnyView() {
        FieldHintResolver resolver = new FieldHintResolver(List.of(new StaffView(), new CustomerView()));

        // The live-validation feed carries no profile; a form rendered from the customer view must
        // still reach its validator.
        assertThat(resolver.validation(Project.class, "staffCheck")).isNotNull();
        assertThat(resolver.validation(Project.class, "customerCheck")).isNotNull();
        assertThat(resolver.validation(Project.class, "missing")).isNull();
    }
}
