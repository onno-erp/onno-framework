package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormValidationMetadataTest {
    @Document(name = "ValidatedSchedules")
    static class Schedule extends DocumentObject {
        @Attribute
        private String room;
    }

    static class Conflicts implements FormValidator {
        @Override
        public List<FormFeedback> validate(FormValidationContext context) {
            return List.of(FormFeedback.warning("room", "Already occupied"));
        }
    }

    static class ScheduleView implements EntityView {
        @Override public Class<?> entity() { return Schedule.class; }
        @Override public void fields(EntityConfigBuilder fields) {
            fields.validation("schedule-conflicts", Conflicts.class)
                    .dependsOn("room", "participants.employee")
                    .debounce(Duration.ofMillis(175));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsDependenciesAndDebounceWithoutExposingImplementationClass() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        var descriptor = scanner.scanDocument(Schedule.class);
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(descriptor);
        var metadata = new ResolvedMetadataService(
                registry, new FieldHintResolver(List.of(new ScheduleView())));

        List<Map<String, Object>> validations = (List<Map<String, Object>>)
                metadata.describeDocument(descriptor).get("formValidations");

        assertThat(validations).containsExactly(Map.of(
                "key", "schedule-conflicts",
                "dependencies", List.of("room", "participants.employee"),
                "debounceMillis", 175L));
    }

    @Test
    void invokesSpringValidatorAndKeepsNullFormValues() {
        Conflicts validator = new Conflicts();
        FormValidation definition = new FormValidation(
                "schedule-conflicts", Conflicts.class, List.of("room"), 200);
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("room", null);

        List<FormFeedback> result = new FormValidationService(List.of(validator)).validate(
                definition, new FormValidationContext(
                        "document", "ValidatedSchedules", Schedule.class, null, values));

        assertThat(result).containsExactly(FormFeedback.warning("room", "Already occupied"));
    }
}
