package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cascading picker predicate ({@code .field(...).refFilter(...)}) flows from the
 * {@link EntityView} DSL into the resolved attribute metadata as {@code refFilter}, with each
 * clause's left-hand side rewritten from the ref <em>target's</em> field name to its column name —
 * {@code ${...}} placeholders and unknown names pass through untouched.
 */
class RefFilterMetadataTest {

    @Catalog(name = "RfDepartments")
    static class Department extends CatalogObject {
        @Attribute(length = 40)
        private String regionCode; // column region_code — proves the field→column rewrite
    }

    @Catalog(name = "RfEmployees")
    static class Employee extends CatalogObject {
        @Attribute
        private Ref<Department> department;
    }

    static class EmployeeView implements EntityView {
        @Override
        public Class<?> entity() {
            return Employee.class;
        }

        @Override
        public void fields(EntityConfigBuilder f) {
            f.field("department").refFilter("regionCode = ${region} AND unknownField = true");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void describeCatalog_rewritesRefFilterFieldNamesToTargetColumns() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Department.class));
        registry.registerCatalog(scanner.scan(Employee.class));
        ResolvedMetadataService svc =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(new EmployeeView())));

        Map<String, Object> described = svc.describeCatalog(scanner.scan(Employee.class));
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) described.get("attributes");
        Map<String, Object> department = attrs.stream()
                .filter(a -> "department".equals(a.get("fieldName")))
                .findFirst().orElseThrow();

        // regionCode resolves to the target's region_code column; the ${region} placeholder and the
        // unrecognized clause pass through for the client / WidgetFilter to deal with.
        assertThat(department.get("refFilter"))
                .isEqualTo("region_code = ${region} AND unknownField = true");
    }
}
