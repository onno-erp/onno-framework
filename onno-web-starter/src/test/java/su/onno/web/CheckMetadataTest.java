package su.onno.web;

import su.onno.validation.CheckRegistry;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.validation.CheckContext;
import su.onno.validation.CheckSpec;
import su.onno.validation.Checks;
import su.onno.validation.Feedback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CheckMetadataTest {
    @Document(name = "ValidatedSchedules")
    static class Schedule extends DocumentObject {
        @Attribute
        private String room;
    }

    static class ScheduleChecks implements Checks<Schedule> {
        final AtomicReference<CheckContext> seen = new AtomicReference<>();

        @Override public Class<Schedule> entity() { return Schedule.class; }

        @Override public void define(CheckSpec<Schedule> checks) {
            checks.check("schedule-conflicts", ctx -> {
                        seen.set(ctx);
                        return List.of(Feedback.warning("room", "Already occupied"), Feedback.info("", ""));
                    })
                    .dependsOn("room")
                    .andDependsOn("participants.employee")
                    .debounce(Duration.ofMillis(175));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsDependenciesAndDebounceWithoutExposingTheCheck() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        var descriptor = scanner.scanDocument(Schedule.class);
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(descriptor);
        var metadata = new ResolvedMetadataService(
                registry, new CheckRegistry(List.of(new ScheduleChecks())));

        List<Map<String, Object>> checks = (List<Map<String, Object>>)
                metadata.describeDocument(descriptor).get("formValidations");

        assertThat(checks).containsExactly(Map.of(
                "key", "schedule-conflicts",
                "dependencies", List.of("room", "participants.employee"),
                "debounceMillis", 175L));
    }

    @Test
    void runsTheCheckLambdaWithLiveValuesAndDropsBlankMessages() {
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        var descriptor = scanner.scanDocument(Schedule.class);
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerDocument(descriptor);
        ScheduleChecks bean = new ScheduleChecks();
        CheckController controller = new CheckController(
                new CatalogQueryService(registry, null), new DocumentQueryService(registry, null),
                new AccessService(registry), new CheckRegistry(List.of(bean)));
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("room", null);

        List<Feedback> result = controller.check("documents", "ValidatedSchedules", "schedule-conflicts",
                new CheckController.Request(null, values), AccessSubject.user("admin", Set.of("ADMIN")));

        assertThat(result).containsExactly(Feedback.warning("room", "Already occupied"));
        assertThat(bean.seen.get().values()).containsEntry("room", null);
        assertThat(bean.seen.get().kind()).isEqualTo("document");
        assertThat(bean.seen.get().entityType()).isEqualTo(Schedule.class);
    }
}
