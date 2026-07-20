package su.onno.ui;

import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RefOptionMetadataTest {

    @Catalog(name = "OptionEmployees")
    static class Employee extends CatalogObject {
    }

    @Document(name = "OptionSchedules")
    static class Schedule extends DocumentObject {
        @TabularSection(name = "participants")
        private List<Participant> participants = new ArrayList<>();
    }

    static class Participant extends TabularSectionRow {
        @Attribute
        private Ref<Employee> employee;
    }

    static class Availability implements RefOptionDecorator {
        @Override
        public Map<java.util.UUID, RefOptionDecoration> decorate(
                RefOptionContext context, List<RefOption> options) {
            return Map.of();
        }
    }

    static class ScheduleView implements EntityView {
        @Override
        public Class<?> entity() {
            return Schedule.class;
        }

        @Override
        public void fields(EntityConfigBuilder fields) {
            fields.field("participants.employee")
                    .refOptions(Availability.class)
                    .uniqueWithinSection();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionReferenceCarriesDecoratorAndUniquenessHints() {
        MetadataRegistry registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerCatalog(scanner.scan(Employee.class));
        registry.registerDocument(scanner.scanDocument(Schedule.class));
        ResolvedMetadataService metadata =
                new ResolvedMetadataService(registry, new FieldHintResolver(List.of(new ScheduleView())));

        Map<String, Object> described = metadata.describeDocument(scanner.scanDocument(Schedule.class));
        List<Map<String, Object>> sections = (List<Map<String, Object>>) described.get("tabularSections");
        List<Map<String, Object>> attributes =
                (List<Map<String, Object>>) sections.getFirst().get("attributes");
        Map<String, Object> employee = attributes.getFirst();

        assertThat(employee)
                .containsEntry("refOptionDecorator", Availability.class.getName())
                .containsEntry("uniqueWithinSection", true);
    }
}
